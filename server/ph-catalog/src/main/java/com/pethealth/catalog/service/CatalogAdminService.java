package com.pethealth.catalog.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pethealth.api.catalog.ServiceCategoryRequest;
import com.pethealth.api.catalog.ServiceCategoryView;
import com.pethealth.api.catalog.ServiceItemRequest;
import com.pethealth.api.catalog.ServiceItemView;
import com.pethealth.catalog.api.CatalogItemApi;
import com.pethealth.catalog.api.CatalogQueryApi;
import com.pethealth.catalog.api.Price;
import com.pethealth.catalog.api.PriceRange;
import com.pethealth.catalog.domain.ServiceCategory;
import com.pethealth.catalog.domain.ServiceItem;
import com.pethealth.catalog.mapper.ServiceCategoryMapper;
import com.pethealth.catalog.mapper.ServiceItemMapper;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.util.Text;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 标准目录的写：分类与项目的维护（运营侧），以及目录外服务提案通过后建正式项目。
 *
 * <p>三条不变量在这里守（ADR-0034，项目所有者 2026-09-29 拍板）：
 *
 * <ul>
 *   <li><b>编码不可改</b>：更新时若编码变了直接 40001。编码已经印进
 *       {@code provider_service.service_code} 与将来的订单项，改码等于改历史语义。
 *   <li><b>区间的两个边界都要校验</b>：下限 ≤ 上限、非负、最多两位小数——运营写反了要在
 *       写入口就拦住，而不是等某个服务者定价时才以「越界」的形式暴露。
 *   <li><b>分类与项目不做物理删除</b>：停用只改状态。分类下还有项目时连停用都要慎用
 *       （本期允许停用，但项目不会因此消失，见 ADR-0034 的代价一节）。
 * </ul>
 */
@Service
public class CatalogAdminService implements CatalogItemApi {

    private final ServiceCategoryMapper categoryMapper;
    private final ServiceItemMapper itemMapper;
    private final CatalogQueryService queryService;

    public CatalogAdminService(ServiceCategoryMapper categoryMapper, ServiceItemMapper itemMapper,
                              CatalogQueryService queryService) {
        this.categoryMapper = categoryMapper;
        this.itemMapper = itemMapper;
        this.queryService = queryService;
    }

    @Transactional
    public ServiceCategoryView createCategory(ServiceCategoryRequest request) {
        String code = request.code().trim();
        requirePrefixFree(request.itemCodePrefix(), code);
        requireCategoryCodeFree(code);

        ServiceCategory category = new ServiceCategory();
        category.setCode(code);
        category.setItemCodePrefix(request.itemCodePrefix());
        category.setName(request.name().trim());
        category.setIcon(Text.trimToNull(request.icon()));
        category.setDescription(Text.trimToNull(request.description()));
        category.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        category.setStatus(ServiceCategory.STATUS_ENABLED);
        categoryMapper.insert(category);
        return CatalogQueryService.toView(category, 0);
    }

    @Transactional
    public ServiceCategoryView updateCategory(long categoryId, ServiceCategoryRequest request) {
        ServiceCategory category = requireCategory(categoryId);
        if (!category.getCode().equals(request.code().trim())) {
            throw BusinessException.paramInvalid("分类编码不可变更（项目与券适用范围都引用它）");
        }
        if (!category.getItemCodePrefix().equals(request.itemCodePrefix())) {
            throw BusinessException.paramInvalid("分类的编码前缀不可变更（已发布的项目编码挂在它上面）");
        }
        category.setName(request.name().trim());
        category.setIcon(Text.trimToNull(request.icon()));
        category.setDescription(Text.trimToNull(request.description()));
        if (request.sortOrder() != null) {
            category.setSortOrder(request.sortOrder());
        }
        categoryMapper.updateById(category);
        return CatalogQueryService.toView(category, countEnabledItems(category.getCode()));
    }

    @Transactional
    public ServiceItemView createItem(ServiceItemRequest request) {
        ServiceCategory category = requireEnabledCategory(request.categoryCode());
        String code = request.code().trim();
        if (queryService.findByCode(code) != null) {
            throw BusinessException.conflict("目录项编码 " + code + " 已存在（编码不可复用）");
        }
        ServiceItem item = new ServiceItem();
        item.setCode(code);
        item.setCategoryCode(category.getCode());
        applyEditableFields(item, request);
        item.setSource(ServiceItem.SOURCE_PLATFORM);
        item.setStatus(ServiceItem.STATUS_ENABLED);
        itemMapper.insert(item);
        return CatalogQueryService.toView(item, category.getName());
    }

    @Transactional
    public ServiceItemView updateItem(long itemId, ServiceItemRequest request) {
        ServiceItem item = requireItem(itemId);
        if (!item.getCode().equals(request.code().trim())) {
            throw BusinessException.paramInvalid("目录项编码不可变更（订单项与券适用范围都引用它）");
        }
        if (!item.getCategoryCode().equals(request.categoryCode().trim())) {
            // 编码的第一段就是分类前缀（HE 属于医院），换分类必然要换码，而编码不可改
            throw BusinessException.paramInvalid("目录项所属分类不可变更（编码前缀与分类绑定）");
        }
        applyEditableFields(item, request);
        itemMapper.updateById(item);
        return CatalogQueryService.toView(item, queryService.categoryNames().get(item.getCategoryCode()));
    }

    /**
     * 启用 / 停用目录项。
     *
     * <p>停用**不会**动服务者已上架的服务项：那些行保留原状、照常对外（C 端接口在下一波）。
     * 自动下架会打断已预约的订单，而没有推送通道去通知用户——代价写在 ADR-0034。
     */
    @Transactional
    public ServiceItemView updateItemStatus(long itemId, int status) {
        ServiceItem item = requireItem(itemId);
        item.setStatus(status);
        itemMapper.updateById(item);
        return CatalogQueryService.toView(item, queryService.categoryNames().get(item.getCategoryCode()));
    }

    @Override
    @Transactional
    public CatalogQueryApi.ItemInfo createFromProposal(CatalogItemApi.Draft draft) {
        ServiceCategory category = requireEnabledCategory(draft.categoryCode());
        PriceRange range = new PriceRange(draft.priceMin(), draft.priceMax());
        if (!range.isValid()) {
            throw BusinessException.paramInvalid("价格区间下限不能高于上限");
        }
        String code = Text.trimToNull(draft.requestedCode()) == null
                ? nextCode(category.getItemCodePrefix())
                : requireUsableCode(category.getItemCodePrefix(), draft.requestedCode().trim());

        ServiceItem item = new ServiceItem();
        item.setCode(code);
        item.setCategoryCode(category.getCode());
        item.setName(draft.name().trim());
        item.setPriceMin(draft.priceMin());
        item.setPriceMax(draft.priceMax());
        item.setPriceUnit(draft.priceUnit());
        item.setApplicablePets(3);
        item.setDescription(Text.trimToNull(draft.description()));
        // 来源标成「提案通过」：这条项目不是平台自建的，社区/运营要能回溯它的来路
        item.setSource(ServiceItem.SOURCE_PROPOSAL);
        item.setSortOrder(0);
        item.setStatus(ServiceItem.STATUS_ENABLED);
        itemMapper.insert(item);
        return CatalogQueryService.toInfo(item, category.getName());
    }

    /** 可编辑字段的写入规则只有这一份：创建与更新共用（两处各写一遍就是两处会漂）。 */
    private void applyEditableFields(ServiceItem item, ServiceItemRequest request) {
        BigDecimal min = Price.parse(request.priceMin(), "价格区间下限");
        BigDecimal max = Price.parse(request.priceMax(), "价格区间上限");
        if (min.compareTo(max) > 0) {
            throw BusinessException.paramInvalid("价格区间下限不能高于上限");
        }
        item.setName(request.name().trim());
        item.setPriceMin(min);
        item.setPriceMax(max);
        item.setPriceUnit(Text.trimToNull(request.priceUnit()) == null ? "次" : request.priceUnit().trim());
        item.setDurationMinutes(request.durationMinutes());
        item.setApplicablePets(request.applicablePets() == null ? 3 : request.applicablePets());
        item.setDescription(Text.trimToNull(request.description()));
        if (request.sortOrder() != null) {
            item.setSortOrder(request.sortOrder());
        }
    }

    /**
     * 排下一个编码：分类前缀 + 三位序号。
     *
     * <p>取 {@code MAX(code)} 而不是「计数 + 1」：删过的码不能复用（ADR-0034），而计数在
     * 有跳号时会撞上已存在的码。真正的并发安全由 {@code uk_code} 唯一键兜底——
     * 两个人同时提案时后一个会拿到冲突，让运营重试，比悄悄覆盖一个已发布的编码安全。
     */
    private String nextCode(String prefix) {
        String maxCode = itemMapper.maxCodeWithPrefix(prefix);
        int nextSerial = 1;
        if (maxCode != null && maxCode.length() >= 3) {
            try {
                nextSerial = Integer.parseInt(maxCode.substring(maxCode.length() - 3)) + 1;
            } catch (NumberFormatException e) {
                // 历史上手工录入过不规范的编码（如 HE-1）：不猜，从 1 开始试到空位
                nextSerial = 1;
            }
        }
        for (int serial = nextSerial; serial < 1000; serial++) {
            String candidate = "%s-%03d".formatted(prefix, serial);
            if (queryService.findByCode(candidate) == null) {
                return candidate;
            }
        }
        throw BusinessException.conflict("分类 " + prefix + " 的编码已用尽（最多 999 个项目）");
    }

    private String requireUsableCode(String prefix, String code) {
        if (!code.startsWith(prefix + "-")) {
            throw BusinessException.paramInvalid("项目编码必须以 " + prefix + "- 开头（该分类的编码前缀）");
        }
        if (queryService.findByCode(code) != null) {
            throw BusinessException.conflict("目录项编码 " + code + " 已被占用（编码不可复用，请换一个）");
        }
        return code;
    }

    private ServiceCategory requireCategory(long categoryId) {
        ServiceCategory category = categoryMapper.selectById(categoryId);
        if (category == null) {
            throw BusinessException.notFound();
        }
        return category;
    }

    private ServiceCategory requireEnabledCategory(String categoryCode) {
        String code = Text.trimToNull(categoryCode);
        Map<String, ServiceCategory> categories = queryService.categoriesByCode();
        ServiceCategory category = code == null ? null : categories.get(code);
        if (category == null) {
            throw BusinessException.paramInvalid("分类 " + categoryCode + " 不存在");
        }
        if (!category.isEnabled()) {
            throw BusinessException.paramInvalid("分类 " + categoryCode + " 已停用，不能在其下新增项目");
        }
        return category;
    }

    private ServiceItem requireItem(long itemId) {
        ServiceItem item = itemMapper.selectById(itemId);
        if (item == null) {
            throw BusinessException.notFound();
        }
        return item;
    }

    private void requireCategoryCodeFree(String code) {
        if (categoryMapper.selectCount(Wrappers.<ServiceCategory>lambdaQuery()
                .eq(ServiceCategory::getCode, code)) > 0) {
            throw BusinessException.conflict("分类编码 " + code + " 已存在");
        }
    }

    private void requirePrefixFree(String prefix, String code) {
        if (categoryMapper.selectCount(Wrappers.<ServiceCategory>lambdaQuery()
                .eq(ServiceCategory::getItemCodePrefix, prefix)) > 0) {
            throw BusinessException.conflict("编码前缀 " + prefix + " 已被其它分类使用（编码不可复用）");
        }
    }

    private long countEnabledItems(String categoryCode) {
        return itemMapper.selectCount(Wrappers.<ServiceItem>lambdaQuery()
                .eq(ServiceItem::getCategoryCode, categoryCode)
                .eq(ServiceItem::getStatus, ServiceItem.STATUS_ENABLED));
    }
}
