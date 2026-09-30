package com.pethealth.provider.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pethealth.api.provider.AllianceCategoryRequest;
import com.pethealth.api.provider.AllianceCategoryStatusRequest;
import com.pethealth.api.provider.AllianceCategoryView;
import com.pethealth.api.provider.ProviderAllianceRequest;
import com.pethealth.api.provider.ProviderProfileView;
import com.pethealth.common.crypto.FieldCipher;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.util.Text;
import com.pethealth.provider.domain.AllianceCategory;
import com.pethealth.provider.domain.AllianceCategoryStat;
import com.pethealth.provider.domain.Provider;
import com.pethealth.provider.domain.ProviderReviewLog;
import com.pethealth.provider.mapper.AllianceCategoryMapper;
import com.pethealth.provider.mapper.ProviderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 服务者联盟分类维度：运营维护维度、指定门店归属。契约见 contract/admin.yaml 的
 * {@code /alliance-categories} 与 {@code /providers/{provider_id}/alliance}。
 *
 * <h2>它为什么需要存在</h2>
 *
 * <p>改造前 {@code provider.category} 是一个只在提交入驻申请时写一次的列，两端都没有修改入口
 * ——也就是说「分类」在库里存在，但在运营手里不存在。本类补的就是这一段：维度可增改与启停、
 * 归属可指定。
 *
 * <h2>三条口径</h2>
 *
 * <ul>
 *   <li><b>值域来自表，不来自注解</b>：{@code provider.category} 的合法取值由这张表定义，
 *       所以校验是「查表 + 启用中」，而不是代码里的 {@code @Min/@Max}。运营新增一档之后
 *       立刻可分配，不需要发版；
 *   <li><b>停用不移动既有归属</b>：停用只挡新指定（{@link #assign} 会拒绝），
 *       已在档上的门店照旧显示该维度名——否则一次停用会让历史归属在界面上凭空消失；
 *   <li><b>归属动作留审核流水</b>：走 {@link ReviewLogRecorder}（{@code ACTION_ASSIGN_ALLIANCE}），
 *       与状态处置同一张 append-only 表。改归属会进考核与流量分配的输入，
 *       它属于「事后要能查是谁改的」那一类动作。
 * </ul>
 *
 * <p><b>为什么改归属只有运营能做</b>：联盟分类是平台侧的分类（交付文档 2.2 的「审核服务者 /
 * 服务」一列把经营主体分类划在平台侧），不是门店自填的画像。服务者后台只读展示自己的归属。
 */
@Service
public class AllianceCategoryService {

    private final AllianceCategoryMapper categoryMapper;
    private final ProviderMapper providerMapper;
    private final ProviderAccess access;
    private final ReviewLogRecorder reviewLog;
    private final FieldCipher cipher;

    public AllianceCategoryService(AllianceCategoryMapper categoryMapper, ProviderMapper providerMapper,
                                   ProviderAccess access, ReviewLogRecorder reviewLog, FieldCipher cipher) {
        this.categoryMapper = categoryMapper;
        this.providerMapper = providerMapper;
        this.access = access;
        this.reviewLog = reviewLog;
        this.cipher = cipher;
    }

    /** 维度列表。运营看全部（含停用），因为停用的维度仍然承载着既有归属。 */
    @Transactional(readOnly = true)
    public List<AllianceCategoryView> list() {
        CurrentUser.requireAdmin();
        List<AllianceCategory> categories = categoryMapper.selectList(
                Wrappers.<AllianceCategory>lambdaQuery()
                        .orderByAsc(AllianceCategory::getSortOrder)
                        .orderByAsc(AllianceCategory::getId));
        Map<Integer, Long> counts = countsByCategory();
        return categories.stream()
                .map(category -> toView(category, counts.getOrDefault(category.getId().intValue(), 0L)))
                .toList();
    }

    /**
     * 新增一档维度。
     *
     * <p>编码与名称都查重：编码是稳定标识（前端与报表引用它），名称是运营看的那一列，
     * 两者重复都会让「说的是哪一档」变成猜谜。库上两个唯一键是最后一道闸，
     * 这里先查一次是为了给出能读懂的中文提示，而不是让唯一键冲突冒成 500。
     */
    @Transactional
    public AllianceCategoryView create(AllianceCategoryRequest request) {
        CurrentUser.requireAdmin();
        String code = request.code().trim();
        String name = request.name().trim();
        requireCodeFree(code);
        requireNameFree(name, null);

        AllianceCategory category = new AllianceCategory();
        category.setCode(code);
        category.setName(name);
        category.setDescription(Text.trimToNull(request.description()));
        category.setSortOrder(request.sortOrder() == null ? nextSortOrder() : request.sortOrder());
        category.setEnabled(AllianceCategory.ENABLED);
        categoryMapper.insert(category);
        return toView(category, 0L);
    }

    /**
     * 改名称 / 说明 / 顺序。**不接受改编码**：编码是稳定标识，改了等于换了一档维度，
     * 而挂在它上面的门店不会跟着走——那正是这个接口要避免的事。改档位语义请停用旧档、新增新档。
     */
    @Transactional
    public AllianceCategoryView update(long categoryId, AllianceCategoryRequest request) {
        CurrentUser.requireAdmin();
        AllianceCategory category = requireById(categoryId);
        String name = request.name().trim();
        requireNameFree(name, categoryId);

        category.setName(name);
        category.setDescription(Text.trimToNull(request.description()));
        if (request.sortOrder() != null) {
            category.setSortOrder(request.sortOrder());
        }
        categoryMapper.updateById(category);
        return toView(category, countsByCategory().getOrDefault((int) categoryId, 0L));
    }

    /** 启用 / 停用。停用不移动既有归属（见类注释），所以这里只改一个标记位。 */
    @Transactional
    public AllianceCategoryView changeStatus(long categoryId, AllianceCategoryStatusRequest request) {
        CurrentUser.requireAdmin();
        AllianceCategory category = requireById(categoryId);
        int target = request.enabled() == null ? -1 : request.enabled();
        if (target != AllianceCategory.ENABLED && target != AllianceCategory.DISABLED) {
            throw BusinessException.paramInvalid("启用状态只能是 0（停用）或 1（启用）");
        }
        if (category.getEnabled() != null && category.getEnabled() == target) {
            throw BusinessException.conflict(target == AllianceCategory.ENABLED ? "该维度已是启用状态" : "该维度已是停用状态");
        }
        category.setEnabled(target);
        categoryMapper.updateById(category);
        return toView(category, countsByCategory().getOrDefault((int) categoryId, 0L));
    }

    /**
     * 指定一个服务者的联盟归属。
     *
     * <p>目标维度必须是**启用中**的：停用档不能再被指定（既有归属不受影响）。
     * 归属变更进审核流水，理由见类注释。
     */
    @Transactional
    public ProviderProfileView assign(long providerId, ProviderAllianceRequest request) {
        CurrentUser.requireAdmin();
        Provider provider = access.requireById(providerId);
        int categoryId = request.category() == null ? -1 : request.category();
        AllianceCategory category = requireById(categoryId);
        if (category.getEnabled() == null || category.getEnabled() != AllianceCategory.ENABLED) {
            throw BusinessException.paramInvalid("「" + category.getName() + "」已停用，不能作为新的归属");
        }
        int current = provider.getCategory() == null ? -1 : provider.getCategory();
        if (current == categoryId) {
            throw BusinessException.conflict("该服务者已经归属「" + category.getName() + "」，无需变更");
        }
        provider.setCategory(categoryId);
        providerMapper.updateById(provider);
        reviewLog.record(ProviderReviewLog.TARGET_PROVIDER, provider.getId(), provider.getId(),
                ProviderReviewLog.ACTION_ASSIGN_ALLIANCE,
                "联盟分类由 " + current + " 改为 " + categoryId + "（" + category.getName() + "）");
        return ProviderViews.toProfileView(provider, cipher, category.getName());
    }

    // ---------------------------------------------------------------- 给别的 service 用的读法

    /**
     * 值 → 名称。给视图映射用（{@code ProviderProfileView.categoryName}）。
     * 查不到的维度回 {@code null} 而不是「其它」：认不出说明数据或代码有一处旧了，
     * 静默显示成「其它」会让这种不一致永远没人发现（与 {@code Provider.typeName} 同一条纪律）。
     */
    @Transactional(readOnly = true)
    public String nameOf(Integer categoryId) {
        if (categoryId == null) {
            return null;
        }
        AllianceCategory category = categoryMapper.selectById(categoryId);
        return category == null ? null : category.getName();
    }

    /**
     * 一批值的「值 → 名称」映射。列表页用它一次取回，避免逐行查库。
     *
     * <p>返回的 Map 用 {@link java.util.HashMap} 而不是 {@code Map.of}：值可能缺席
     * （历史数据里指向已不存在维度的 category），而 {@code Map.of} 不容 null 值，
     * 调用方一处 {@code getOrDefault} 就会踩到 NPE。**值本身不会是 null**——
     * 查不到的行**不进 Map**，调用方拿 {@code get} 的 null 走 {@code getOrDefault} 或直接判空。
     */
    @Transactional(readOnly = true)
    public Map<Integer, String> namesOf(Collection<Integer> categoryIds) {
        Set<Integer> wanted = new LinkedHashSet<>();
        for (Integer id : categoryIds) {
            if (id != null) {
                wanted.add(id);
            }
        }
        if (wanted.isEmpty()) {
            return Map.of();
        }
        List<AllianceCategory> categories = categoryMapper.selectBatchIds(wanted);
        Map<Integer, String> names = new HashMap<>();
        for (AllianceCategory category : categories) {
            names.put(category.getId().intValue(), category.getName());
        }
        return names;
    }

    /**
     * 校验一个归属值合法且可用（存在 + 启用中）。入驻申请提交与修改走这里。
     *
     * <p>返回维度本身，调用方通常接着要它的名称，省一次查。
     */
    @Transactional(readOnly = true)
    public AllianceCategory requireEnabled(Integer categoryId) {
        if (categoryId == null) {
            // 申请单里 category 可省（老版本客户端），省略时按「归到第一档」处理——
            // 这条默认在 OnboardingService 里，不在这里，免得这个方法的语义变成两种
            throw BusinessException.paramInvalid("联盟分类不能为空");
        }
        AllianceCategory category = categoryMapper.selectById(categoryId);
        if (category == null) {
            throw BusinessException.paramInvalid("联盟分类不存在或已被清理");
        }
        if (category.getEnabled() == null || category.getEnabled() != AllianceCategory.ENABLED) {
            throw BusinessException.paramInvalid("联盟分类「" + category.getName() + "」已停用，不能指派");
        }
        return category;
    }

    /** 默认归属（申请单没填 category 时用）：顺序最小的启用维度。 */
    @Transactional(readOnly = true)
    public int defaultCategoryId() {
        AllianceCategory first = categoryMapper.selectOne(
                Wrappers.<AllianceCategory>lambdaQuery()
                        .eq(AllianceCategory::getEnabled, AllianceCategory.ENABLED)
                        .orderByAsc(AllianceCategory::getSortOrder)
                        .orderByAsc(AllianceCategory::getId)
                        .last("LIMIT 1"));
        if (first == null) {
            // 没有启用维度时给 1：它是种子里的「直接同业」，也是 provider.category 的历史默认。
            // 这里不抛异常——一个被停空的维度表不该让入驻申请提交不了。
            return 1;
        }
        return first.getId().intValue();
    }

    // ---------------------------------------------------------------- 内部

    private AllianceCategory requireById(long categoryId) {
        AllianceCategory category = categoryMapper.selectById(categoryId);
        if (category == null) {
            throw BusinessException.notFound("联盟分类不存在");
        }
        return category;
    }

    private void requireCodeFree(String code) {
        Long exists = categoryMapper.selectCount(
                Wrappers.<AllianceCategory>lambdaQuery().eq(AllianceCategory::getCode, code));
        if (exists != null && exists > 0) {
            throw BusinessException.conflict("维度编码「" + code + "」已存在");
        }
    }

    private void requireNameFree(String name, Long selfId) {
        Long exists = categoryMapper.selectCount(
                Wrappers.<AllianceCategory>lambdaQuery()
                        .eq(AllianceCategory::getName, name)
                        .ne(selfId != null, AllianceCategory::getId, selfId));
        if (exists != null && exists > 0) {
            throw BusinessException.conflict("维度名称「" + name + "」已存在");
        }
    }

    /** 没给顺序时排到末尾：新档默认排在既有档之后，运营要调再调。 */
    private int nextSortOrder() {
        Long existing = categoryMapper.selectCount(null);
        return (existing == null ? 0 : existing.intValue()) + 1;
    }

    private Map<Integer, Long> countsByCategory() {
        Map<Integer, Long> counts = new HashMap<>();
        for (AllianceCategoryStat stat : categoryMapper.countByCategory()) {
            if (stat.getCategoryId() != null) {
                counts.put(stat.getCategoryId(), stat.getProviderCount() == null ? 0L : stat.getProviderCount());
            }
        }
        return counts;
    }

    private AllianceCategoryView toView(AllianceCategory category, long providerCount) {
        return new AllianceCategoryView(
                category.getId(),
                category.getCode(),
                category.getName(),
                category.getDescription(),
                category.getSortOrder(),
                category.getEnabled(),
                providerCount,
                category.getCreatedAt(),
                category.getUpdatedAt());
    }
}
