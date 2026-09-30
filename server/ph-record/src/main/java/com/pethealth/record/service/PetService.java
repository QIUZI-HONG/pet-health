package com.pethealth.record.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pethealth.api.app.PetCreateRequest;
import com.pethealth.api.app.PetUpdateRequest;
import com.pethealth.api.app.PetView;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.time.AppTime;
import com.pethealth.common.trace.TraceIds;
import com.pethealth.common.util.Text;
import com.pethealth.api.record.PetFactsApi;
import com.pethealth.privilege.api.InviteAttributionApi;
import com.pethealth.record.api.AiPetApi;
import com.pethealth.record.api.PetQueryApi;
import com.pethealth.record.domain.CareMode;
import com.pethealth.record.domain.Pet;
import com.pethealth.record.domain.Weight;
import com.pethealth.record.mapper.PetMapper;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 宠物档案：建档、编辑、多宠、软删除与 30 天恢复（切片 #94）。
 *
 * <p>两条贯穿全类的规则：
 *
 * <ul>
 *   <li><b>归属校验</b>：所有单只宠物的读写都带 {@code user_id}，查不到就是 40400。
 *       「不存在」与「不是他的」对外是同一个结果（契约里写明了，别改成 403）。
 *   <li><b>恢复期</b>：软删除后 30 天内可恢复，边界由 {@link #RESTORE_WINDOW_DAYS} 决定。
 * </ul>
 *
 * <p>{@code @Primary} 是为了 {@link PetFactsApi}：集成测试里有一个等价的桩
 * （{@code OrderTestSupport.StubCrossModuleApis}），而取用方（{@code OrderParties}）用
 * {@code ObjectProvider} 就地取实现——两个候选且无主时会抛
 * {@code NoUniqueBeanDefinitionException}，把「有实现」变成 500，正好抵消接线的意义
 * （与 {@code ProviderAccessAdapter} 同一处取舍）。
 */
@Service
@Primary
public class PetService implements PetQueryApi, AiPetApi, PetFactsApi {

    /** 交付文档与用户故事 5 都写明「30 天内可恢复」。 */
    public static final int RESTORE_WINDOW_DAYS = 30;

    private final PetMapper petMapper;
    private final CareModeService careModeService;
    private final HealthScoreService healthScoreService;
    private final InviteAttributionApi inviteApi;

    public PetService(PetMapper petMapper, CareModeService careModeService,
                      HealthScoreService healthScoreService, InviteAttributionApi inviteApi) {
        this.petMapper = petMapper;
        this.careModeService = careModeService;
        this.healthScoreService = healthScoreService;
        this.inviteApi = inviteApi;
    }

    @Transactional(readOnly = true)
    public List<PetView> list(long userId, boolean recycleBin) {
        if (recycleBin) {
            return petMapper.selectRecycleBin(userId, restoreDeadline()).stream()
                    .map(PetService::toView)
                    .toList();
        }
        // 排序：时间倒序是项目默认（docs/conventions.md）；再按 id 兜底，保证顺序稳定
        List<Pet> pets = petMapper.selectList(
                Wrappers.<Pet>lambdaQuery()
                        .eq(Pet::getUserId, userId)
                        .orderByDesc(Pet::getCreatedAt)
                        .orderByDesc(Pet::getId));
        return pets.stream().map(PetService::toView).toList();
    }

    @Transactional(readOnly = true)
    public PetView get(long userId, long petId) {
        return toView(requireOwned(userId, petId));
    }

    @Transactional
    public PetView create(long userId, PetCreateRequest request) {
        // 三个布尔字段都可空：没有默认值时按「否」处理，别直接拆箱（null 会 NPE）
        boolean chronic = Boolean.TRUE.equals(request.isChronic());
        boolean sterilized = Boolean.TRUE.equals(request.isSterilized());
        validateChronic(chronic, request.chronicDesc());
        validateBirthday(request.birthday());

        Pet pet = new Pet();
        pet.setUserId(userId);
        pet.setName(request.name().trim());
        pet.setSpecies(request.species());
        pet.setBreed(Text.trimToNull(request.breed()));
        pet.setGender(request.gender() == null ? 0 : request.gender());
        pet.setBirthday(request.birthday());
        pet.setWeight(toWeight(request.weight()));
        pet.setAvatar(Text.trimToNull(request.avatar()));
        pet.setIsSterilized(sterilized ? 1 : 0);
        pet.setIsChronic(chronic ? 1 : 0);
        pet.setChronicDesc(chronic ? Text.trimToNull(request.chronicDesc()) : null);

        petMapper.insert(pet);
        // 「有效邀请」的接线点（ADR-0039 第一节 / ADR-0046「需要协调」第 2 条）：
        // 「完成建档」在本项目里就是建宠物的健康档案，而本方法是它的**唯一入口**（契约
        // POST /api/v1/app/pets）。选这里而不是 ph-account 的「完善档案」（改昵称/头像）：
        // 那边是可选动作，注册完再也不点的人很多；而建档是 BPM-2 里被邀请人真正迈出的第一步，
        // 也是「这个人真的在用平台」的最低证据——反作弊要的就是这个（ADR-0046 第三节）。
        //
        // 与建档在**同一个事务**里：建档成功而关系没推进，被邀请人会在观察窗结束时被判
        // 「24 小时无行为」而无效——那是查不出来的错，也是刷号与真实用户被一起误伤的地方。
        // 因此推进失败就把建档一起回滚（用户重试一次即可），不做静默降级。
        // 幂等：一个被邀请人只归因一次，重复建档（第二只宠物）返回 false，什么都不做。
        inviteApi.markProfileCompleted(userId);
        return toView(pet);
    }

    @Transactional
    public PetView update(long userId, long petId, PetUpdateRequest request) {
        Pet pet = requireOwned(userId, petId);

        if (request.name() != null) {
            if (request.name().isBlank()) {
                throw BusinessException.paramInvalid("宠物昵称不能为空");
            }
            pet.setName(request.name().trim());
        }
        if (request.species() != null) {
            pet.setSpecies(request.species());
        }
        if (request.breed() != null) {
            pet.setBreed(Text.trimToNull(request.breed()));
        }
        if (request.gender() != null) {
            pet.setGender(request.gender());
        }
        if (request.birthday() != null) {
            validateBirthday(request.birthday());
            pet.setBirthday(request.birthday());
        }
        if (request.weight() != null) {
            pet.setWeight(toWeight(request.weight()));
        }
        if (request.avatar() != null) {
            pet.setAvatar(Text.trimToNull(request.avatar()));
        }
        if (request.isSterilized() != null) {
            pet.setIsSterilized(request.isSterilized() ? 1 : 0);
        }
        if (request.isChronic() != null || request.chronicDesc() != null) {
            boolean chronic = request.isChronic() != null ? request.isChronic() : pet.isChronicFlag();
            String desc = request.chronicDesc() != null ? Text.trimToNull(request.chronicDesc()) : pet.getChronicDesc();
            validateChronic(chronic, desc);
            pet.setIsChronic(chronic ? 1 : 0);
            // 取消慢病标记时把描述一起清掉，避免留下一条「没病但有病史」的脏数据
            pet.setChronicDesc(chronic ? desc : null);
        }

        petMapper.updateById(pet);
        // 生日与慢病决定「老年维是否计入总分」（ADR-0032），所以改完要重算当日行——
        // 否则趋势图上会留着一条按旧口径算出来的点，而评分卡（现算）与它对不上。
        healthScoreService.recalculate(petId, AppTime.today());
        return toView(pet);
    }

    /**
     * 专项照护模式状态（切片 #116）。
     *
     * <p>它是**派生结果**：年龄按生日实时算、慢病按用户标记，慢病解除即退出（ADR-0032）。
     * 归属校验照旧（别人的宠物 40400）。
     */
    @Transactional(readOnly = true)
    public com.pethealth.api.app.CareModeView careMode(long userId, long petId) {
        Pet pet = requireOwned(userId, petId);
        return careModeService.toView(careModeService.of(pet, AppTime.today()), pet);
    }

    /**
     * 打开 / 关闭专项照护模式（只用于「用户手动关闭」）。
     *
     * <p>{@code enabled=false} 落一枚「用户关掉了」的位；{@code enabled=true} 是**清除这枚位**、
     * 把判定交还给派生规则——宠物确实不满年龄且没有慢病时，打开后返回的仍是未开启。
     * 改完**重算当日评分行**：老年维的计入与否直接改变总分口径（ADR-0032 决定一）。
     */
    @Transactional
    public com.pethealth.api.app.CareModeView setCareMode(long userId, long petId, boolean enabled) {
        Pet pet = requireOwned(userId, petId);
        pet.setCareModeDisabled(enabled ? 0 : 1);
        petMapper.updateById(pet);
        healthScoreService.recalculate(petId, AppTime.today());
        return careModeService.toView(careModeService.of(pet, AppTime.today()), pet);
    }

    @Transactional
    public void delete(long userId, long petId) {
        int affected = petMapper.softDelete(petId, userId, AppTime.now(),
                TraceIds.currentOperatorId(), TraceIds.currentTraceId());
        if (affected == 0) {
            throw BusinessException.notFound();
        }
    }

    @Transactional
    public PetView restore(long userId, long petId) {
        int affected = petMapper.restore(petId, userId, restoreDeadline(), AppTime.now(),
                TraceIds.currentOperatorId(), TraceIds.currentTraceId());
        if (affected == 0) {
            throw BusinessException.notFound("宠物不存在，或已超过 " + RESTORE_WINDOW_DAYS + " 天恢复期");
        }
        return toView(requireOwned(userId, petId));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsOwnedBy(long userId, long petId) {
        return petMapper.exists(Wrappers.<Pet>lambdaQuery()
                .eq(Pet::getId, petId)
                .eq(Pet::getUserId, userId));
    }

    /**
     * 批量取宠物昵称（{@link PetFactsApi}）：订单在创建时把昵称**快照**下来。
     *
     * <p>为什么是快照而不是每次现取：订单是一份历史凭证，宠物改名之后历史订单上写的仍应是
     * 当时那一只。这与目录项名称「现取不存快照」是相反的口径，理由也相反
     * （ADR-0034 决定 9 约束的是**服务目录**：目录改了，服务者页面与 C 端要同时变）。
     *
     * <p>逻辑删除由 MyBatis-Plus 自动带上（{@code is_deleted = 0}，见 {@code BaseEntity}），
     * 所以回收站里的宠物查不到——正是接口说好的「查不到的 id 不会出现在 Map 里」。
     */
    @Override
    @Transactional(readOnly = true)
    public Map<Long, String> petNames(Collection<Long> petIds) {
        Map<Long, String> names = new HashMap<>();
        for (Pet pet : selectByIds(petIds)) {
            names.put(pet.getId(), pet.getName());
        }
        return names;
    }

    /**
     * 批量取宠物物种码（{@link PetFactsApi}）：服务者侧订单列表要按物种准备工位与耗材。
     *
     * <p>与 {@link #petNames} 分成两个窄方法而不是回一个「宠物事实对象」：下单只用到两个标量，
     * 而 ph-api 里新造 record 会被 {@code ContractDtoDrift} 判成「DTO 有、契约无」。
     */
    @Override
    @Transactional(readOnly = true)
    public Map<Long, Integer> petSpecies(Collection<Long> petIds) {
        Map<Long, Integer> species = new HashMap<>();
        for (Pet pet : selectByIds(petIds)) {
            species.put(pet.getId(), pet.getSpecies());
        }
        return species;
    }

    /**
     * 两个 facts 方法的共同查库部分。
     *
     * <p>空集合**不查库**：{@code IN ()} 会被 MySQL 拒绝，而且调用方的空列表本来就只该得到
     * 一张空 Map——为它走一次数据库往返是纯浪费。
     */
    private List<Pet> selectByIds(Collection<Long> petIds) {
        if (petIds == null || petIds.isEmpty()) {
            return List.of();
        }
        return petMapper.selectBatchIds(petIds);
    }

    /**
     * 宠物快照（{@link AiPetApi}）：给 ph-ai 组装 AI 请求用。
     *
     * <p>越权与不存在一律返回空——调用方按 40400 处理，不区分两种情况（docs/conventions.md）。
     */
    @Override
    public java.util.Optional<AiPetApi.PetSnapshot> snapshotOwnedBy(long userId, long petId) {
        Pet pet = findOwned(userId, petId);
        if (pet == null) {
            return java.util.Optional.empty();
        }
        // 照护状态用**同一处判定**（ADR-0032 决定一），不在这里再写一遍「年龄 ≥ 7 或有慢病」
        CareMode care = careModeService.of(pet, AppTime.today());
        return java.util.Optional.of(new AiPetApi.PetSnapshot(
                pet.getId(),
                pet.getUserId(),
                pet.getSpecies(),
                pet.getBreed(),
                pet.getBirthday(),
                pet.getWeight(),
                pet.getIsChronic() != null && pet.getIsChronic() == 1 ? pet.getChronicDesc() : null,
                care.active(),
                care.ageYears(),
                care.ageText()));
    }

    /**
     * 归属校验：不存在与越权都抛 40400（契约里写明了，别改成 403）。
     *
     * <p>public 是给同模块的其它服务与控制器用的（例如评分要拿到宠物算年龄、打卡要校验归属），
     * 本模块之外不要用——跨模块要走 {@link PetQueryApi}。
     *
     * <p>「按归属取实体」这件事在本模块只留这一处实现：打卡、防疫、评分原先各写了一份
     * 同样的 {@code selectOne(id + user_id)}，四份代码意味着「软删要不要算不存在」
     * 这类口径有四次要同步。
     */
    public Pet requireOwned(long userId, long petId) {
        Pet pet = findOwned(userId, petId);
        if (pet == null) {
            throw BusinessException.notFound();
        }
        return pet;
    }

    /**
     * 按归属查一只宠物，查不到返回 {@code null}（不抛）。
     *
     * <p>给两类调用方用：需要「空」而不是异常的快照路径（{@link #snapshotOwnedBy}），
     * 以及 {@link #requireOwned} 本身。软删除的宠物查不到——MyBatis-Plus 的逻辑删除
     * 已经在 SQL 上过滤（ADR-0011）。
     */
    private Pet findOwned(long userId, long petId) {
        return petMapper.selectOne(Wrappers.<Pet>lambdaQuery()
                .eq(Pet::getId, petId)
                .eq(Pet::getUserId, userId));
    }

    private LocalDateTime restoreDeadline() {
        return AppTime.now().minusDays(RESTORE_WINDOW_DAYS);
    }

    private static PetView toView(Pet pet) {
        boolean deleted = pet.getIsDeleted() != null && pet.getIsDeleted() == 1;
        return new PetView(
                pet.getId(),
                pet.getName(),
                pet.getSpecies(),
                pet.getBreed(),
                pet.getGender(),
                pet.getBirthday(),
                pet.getWeight() == null ? null : pet.getWeight().toPlainString(),
                pet.getAvatar(),
                pet.isSterilizedFlag(),
                pet.isChronicFlag(),
                pet.getChronicDesc(),
                deleted && pet.getDeletedAt() != null
                        ? pet.getDeletedAt().plusDays(RESTORE_WINDOW_DAYS)
                        : null,
                pet.getCreatedAt(),
                pet.getUpdatedAt());
    }

    private void validateChronic(boolean chronic, String desc) {
        if (chronic && (desc == null || desc.isBlank())) {
            throw BusinessException.paramInvalid("标记为慢病时必须填写慢病描述");
        }
    }

    /** 生日不能晚于今天——将来还能再校验一条「不能早于 1990」，现在没有依据，不编。 */
    private void validateBirthday(LocalDate birthday) {
        if (birthday != null && birthday.isAfter(AppTime.today())) {
            throw BusinessException.paramInvalid("生日不能晚于今天");
        }
    }

    /**
     * 体重解析与校验都走 {@link Weight}——范围（0.01–999.99）与形状只在那一个类里定义。
     *
     * <p>这里只补一件事：**归一化到两位小数**。DB 是 {@code DECIMAL(6,2)}，读回来的值天然是两位；
     * 而建档/编辑的响应用的是内存里的实体，不归一化就会返回 {@code "12.5"}——同一个字段两个接口
     * 给出两种格式，前端没法写。（{@link Weight} 已限死最多两位小数，所以这里不会截断用户的值。）
     */
    private BigDecimal toWeight(String weight) {
        BigDecimal parsed = Weight.parse(weight);
        return parsed == null ? null : parsed.setScale(2, RoundingMode.HALF_UP);
    }
}
