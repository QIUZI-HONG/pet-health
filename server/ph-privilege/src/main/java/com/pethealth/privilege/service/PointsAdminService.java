package com.pethealth.privilege.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pethealth.api.privilege.PointsDtos;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.time.AppTime;
import com.pethealth.privilege.domain.CouponTemplate;
import com.pethealth.privilege.domain.PointBehavior;
import com.pethealth.privilege.domain.PointConfig;
import com.pethealth.privilege.domain.PointExchangeOption;
import com.pethealth.privilege.domain.PointLadderTier;
import com.pethealth.privilege.domain.PointRecord;
import com.pethealth.privilege.domain.PointTask;
import com.pethealth.privilege.mapper.CouponTemplateMapper;
import com.pethealth.privilege.mapper.PointBehaviorMapper;
import com.pethealth.privilege.mapper.PointConfigMapper;
import com.pethealth.privilege.mapper.PointExchangeOptionMapper;
import com.pethealth.privilege.mapper.PointLadderTierMapper;
import com.pethealth.privilege.mapper.PointRecordMapper;
import com.pethealth.privilege.mapper.PointTaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 积分的运营侧（切片 #113）：规则设置、行为分值、任务清单、兑换档位、月度阶梯档位与流水查询。
 *
 * <p>三条刻意的口径：
 *
 * <ul>
 *   <li><b>只能改分值 / 频次 / 启停，不能新增行为</b>：行为码要有代码去触发它，
 *       加一行数据只产生一个永远不会发生的动作。新增行为是代码变更（ADR-0046 的决定）；
 *   <li><b>兑换档位必须指向平台补贴券</b>：兑换消耗的是平台的钱，不消耗服务者的贡献额度，
 *       否则等于把兑换成本转嫁给服务者（ADR-0038 第四节）。同理月度阶梯发的也是平台补贴券；
 *   <li><b>任务引用已有行为</b>：任务本身不额外发分，{@code points} 只是展示值——
 *       同一行为发两次奖励正是要钉死的那条。
 * </ul>
 */
@Service
public class PointsAdminService {

    private final PointConfigMapper configMapper;
    private final PointBehaviorMapper behaviorMapper;
    private final PointTaskMapper taskMapper;
    private final PointExchangeOptionMapper optionMapper;
    private final PointLadderTierMapper ladderTierMapper;
    private final PointRecordMapper recordMapper;
    private final CouponTemplateMapper templateMapper;

    public PointsAdminService(PointConfigMapper configMapper, PointBehaviorMapper behaviorMapper,
                              PointTaskMapper taskMapper, PointExchangeOptionMapper optionMapper,
                              PointLadderTierMapper ladderTierMapper, PointRecordMapper recordMapper,
                              CouponTemplateMapper templateMapper) {
        this.configMapper = configMapper;
        this.behaviorMapper = behaviorMapper;
        this.taskMapper = taskMapper;
        this.optionMapper = optionMapper;
        this.ladderTierMapper = ladderTierMapper;
        this.recordMapper = recordMapper;
        this.templateMapper = templateMapper;
    }

    // ---------------------------------------------------------------- 总览与设置

    /** 积分总览（含今日发放与每日上限的实际命中情况）。 */
    @Transactional(readOnly = true)
    public PointsDtos.PointsOverviewView overview() {
        CurrentUser.requireAdmin();
        return PrivilegeViews.toPointsOverview(config(), recordMapper.overview(AppTime.today()));
    }

    /** 规则设置（每日获取上限）。 */
    @Transactional(readOnly = true)
    public PointsDtos.PointsSettingsView settings() {
        CurrentUser.requireAdmin();
        return new PointsDtos.PointsSettingsView(config().getDailyEarnLimit());
    }

    /** 改设置：每日上限是业务可调项（ADR-0010 的三层配置），改它不该发版。 */
    @Transactional
    public PointsDtos.PointsSettingsView updateSettings(PointsDtos.PointsSettingsRequest request) {
        CurrentUser.requireAdmin();
        PointConfig config = config();
        config.setDailyEarnLimit(request.dailyEarnLimit());
        configMapper.updateById(config);
        return new PointsDtos.PointsSettingsView(config.getDailyEarnLimit());
    }

    /** 积分流水（含变动后余额）。 */
    @Transactional(readOnly = true)
    public PageResult<PointsDtos.PointRecordView> listRecords(Long userId, String behaviorCode,
                                                              long page, long pageSize) {
        CurrentUser.requireAdmin();
        Page<PointRecord> result = recordMapper.selectPage(new Page<>(page, pageSize),
                Wrappers.<PointRecord>lambdaQuery()
                        .eq(userId != null, PointRecord::getUserId, userId)
                        .eq(behaviorCode != null && !behaviorCode.isBlank(), PointRecord::getBehaviorCode,
                                behaviorCode == null ? null : behaviorCode.trim())
                        .orderByDesc(PointRecord::getId));
        Map<String, PointBehavior> behaviors = behaviorsOf(result.getRecords());
        return PageResult.from(result, record -> PrivilegeViews.toRecordView(record,
                behaviors.get(record.getBehaviorCode())));
    }

    // ---------------------------------------------------------------- 行为分值

    @Transactional(readOnly = true)
    public List<PointsDtos.PointBehaviorView> listBehaviors() {
        CurrentUser.requireAdmin();
        return behaviorMapper.selectList(Wrappers.<PointBehavior>lambdaQuery()
                        .orderByAsc(PointBehavior::getSortOrder))
                .stream().map(PrivilegeViews::toBehaviorView).toList();
    }

    /** 改行为分值（**不能新增行为**：行为码要有代码去触发它）。 */
    @Transactional
    public PointsDtos.PointBehaviorView updateBehavior(String code, PointsDtos.PointBehaviorRequest request) {
        CurrentUser.requireAdmin();
        PointBehavior behavior = behaviorMapper.selectOne(Wrappers.<PointBehavior>lambdaQuery()
                .eq(PointBehavior::getCode, code));
        if (behavior == null) {
            throw BusinessException.notFound("行为码不存在：" + code + "（新增行为是代码变更，不能在这里加）");
        }
        behavior.setPoints(request.points());
        if (request.countsTowardDailyCap() != null) {
            behavior.setCountsTowardDailyCap(request.countsTowardDailyCap());
        }
        if (request.dailyCountLimit() != null) {
            behavior.setDailyCountLimit(request.dailyCountLimit() == 0 ? null : request.dailyCountLimit());
        }
        if (request.monthlyCountLimit() != null) {
            behavior.setMonthlyCountLimit(request.monthlyCountLimit() == 0 ? null : request.monthlyCountLimit());
        }
        if (request.onceOnly() != null) {
            behavior.setOnceOnly(request.onceOnly());
        }
        if (request.status() != null) {
            behavior.setStatus(request.status());
        }
        behaviorMapper.updateById(behavior);
        return PrivilegeViews.toBehaviorView(behavior);
    }

    // ---------------------------------------------------------------- 任务清单

    @Transactional(readOnly = true)
    public List<PointsDtos.PointTaskView> listTasks() {
        CurrentUser.requireAdmin();
        List<PointTask> tasks = taskMapper.selectList(Wrappers.<PointTask>lambdaQuery()
                .orderByAsc(PointTask::getPeriod)
                .orderByAsc(PointTask::getSortOrder)
                .orderByAsc(PointTask::getId));
        Map<String, PointBehavior> behaviors = new HashMap<>();
        behaviorMapper.selectList(Wrappers.<PointBehavior>lambdaQuery())
                .forEach(behavior -> behaviors.put(behavior.getCode(), behavior));
        return tasks.stream().map(task -> PrivilegeViews.toTaskView(task,
                behaviors.get(task.getBehaviorCode()))).toList();
    }

    /** 新增任务（必须引用已有的行为码；同一档位同一行为只能有一个任务）。 */
    @Transactional
    public PointsDtos.PointTaskView createTask(PointsDtos.PointTaskRequest request) {
        CurrentUser.requireAdmin();
        PointBehavior behavior = requireBehavior(request.behaviorCode());
        if (taskMapper.selectCount(Wrappers.<PointTask>lambdaQuery()
                .eq(PointTask::getCode, request.code())) > 0) {
            throw BusinessException.conflict("任务编码已存在");
        }
        if (taskMapper.selectCount(Wrappers.<PointTask>lambdaQuery()
                .eq(PointTask::getPeriod, request.period())
                .eq(PointTask::getBehaviorCode, behavior.getCode())) > 0) {
            throw BusinessException.conflict("该档位已有这个行为的任务（同一行为只挂一个任务，避免进度有两个来源）");
        }
        PointTask task = new PointTask();
        task.setCode(request.code());
        task.setName(request.name().trim());
        task.setPeriod(request.period());
        task.setBehaviorCode(behavior.getCode());
        task.setTargetCount(request.targetCount());
        task.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        task.setStatus(request.status() == null ? PointTask.STATUS_ENABLED : request.status());
        taskMapper.insert(task);
        return PrivilegeViews.toTaskView(task, behavior);
    }

    /** 修改任务（编码不可改）。 */
    @Transactional
    public PointsDtos.PointTaskView updateTask(long taskId, PointsDtos.PointTaskRequest request) {
        CurrentUser.requireAdmin();
        PointTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw BusinessException.notFound("任务不存在");
        }
        if (!task.getCode().equals(request.code())) {
            throw BusinessException.paramInvalid("任务编码不可变更");
        }
        PointBehavior behavior = requireBehavior(request.behaviorCode());
        task.setName(request.name().trim());
        task.setPeriod(request.period());
        task.setBehaviorCode(behavior.getCode());
        task.setTargetCount(request.targetCount());
        task.setSortOrder(request.sortOrder() == null ? task.getSortOrder() : request.sortOrder());
        task.setStatus(request.status() == null ? task.getStatus() : request.status());
        taskMapper.updateById(task);
        return PrivilegeViews.toTaskView(task, behavior);
    }

    // ---------------------------------------------------------------- 兑换档位

    @Transactional(readOnly = true)
    public List<PointsDtos.PointExchangeOptionView> listExchangeOptions() {
        CurrentUser.requireAdmin();
        List<PointExchangeOption> options = optionMapper.selectList(Wrappers.<PointExchangeOption>lambdaQuery()
                .orderByAsc(PointExchangeOption::getSortOrder)
                .orderByAsc(PointExchangeOption::getId));
        Map<Long, CouponTemplate> templates = templatesOf(options.stream()
                .map(PointExchangeOption::getCouponTemplateId).toList());
        return options.stream().map(option -> PrivilegeViews.toExchangeOptionView(option,
                templates.get(option.getCouponTemplateId()))).toList();
    }

    @Transactional
    public PointsDtos.PointExchangeOptionView createExchangeOption(
            PointsDtos.PointExchangeOptionRequest request) {
        CurrentUser.requireAdmin();
        CouponTemplate template = requireSubsidyTemplate(request.couponTemplateId());
        PointExchangeOption option = new PointExchangeOption();
        option.setName(request.name().trim());
        option.setPointsCost(request.pointsCost());
        option.setCouponTemplateId(template.getId());
        option.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        option.setStatus(request.status() == null ? PointExchangeOption.STATUS_ENABLED : request.status());
        optionMapper.insert(option);
        return PrivilegeViews.toExchangeOptionView(option, template);
    }

    @Transactional
    public PointsDtos.PointExchangeOptionView updateExchangeOption(long optionId,
                                                                   PointsDtos.PointExchangeOptionRequest request) {
        CurrentUser.requireAdmin();
        PointExchangeOption option = optionMapper.selectById(optionId);
        if (option == null) {
            throw BusinessException.notFound("兑换档位不存在");
        }
        CouponTemplate template = requireSubsidyTemplate(request.couponTemplateId());
        option.setName(request.name().trim());
        option.setPointsCost(request.pointsCost());
        option.setCouponTemplateId(template.getId());
        option.setSortOrder(request.sortOrder() == null ? option.getSortOrder() : request.sortOrder());
        option.setStatus(request.status() == null ? option.getStatus() : request.status());
        optionMapper.updateById(option);
        return PrivilegeViews.toExchangeOptionView(option, template);
    }

    // ---------------------------------------------------------------- 月度阶梯档位

    @Transactional(readOnly = true)
    public List<PointsDtos.PointLadderTierView> listLadderTiers() {
        CurrentUser.requireAdmin();
        List<PointLadderTier> tiers = ladderTierMapper.selectList(Wrappers.<PointLadderTier>lambdaQuery()
                .orderByAsc(PointLadderTier::getThresholdPoints)
                .orderByAsc(PointLadderTier::getId));
        Map<Long, CouponTemplate> templates = templatesOf(tiers.stream()
                .map(PointLadderTier::getCouponTemplateId).toList());
        return tiers.stream().map(tier -> PrivilegeViews.toLadderTierView(tier,
                templates.get(tier.getCouponTemplateId()))).toList();
    }

    @Transactional
    public PointsDtos.PointLadderTierView createLadderTier(PointsDtos.PointLadderTierRequest request) {
        CurrentUser.requireAdmin();
        CouponTemplate template = requireSubsidyTemplate(request.couponTemplateId());
        if (ladderTierMapper.selectCount(Wrappers.<PointLadderTier>lambdaQuery()
                .eq(PointLadderTier::getThresholdPoints, request.thresholdPoints())) > 0) {
            throw BusinessException.conflict("该门槛已有档位");
        }
        PointLadderTier tier = new PointLadderTier();
        tier.setThresholdPoints(request.thresholdPoints());
        tier.setCouponTemplateId(template.getId());
        tier.setCouponCount(request.couponCount() == null ? 1 : request.couponCount());
        tier.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        tier.setStatus(request.status() == null ? PointLadderTier.STATUS_ENABLED : request.status());
        ladderTierMapper.insert(tier);
        return PrivilegeViews.toLadderTierView(tier, template);
    }

    @Transactional
    public PointsDtos.PointLadderTierView updateLadderTier(long tierId,
                                                           PointsDtos.PointLadderTierRequest request) {
        CurrentUser.requireAdmin();
        PointLadderTier tier = ladderTierMapper.selectById(tierId);
        if (tier == null) {
            throw BusinessException.notFound("阶梯档位不存在");
        }
        CouponTemplate template = requireSubsidyTemplate(request.couponTemplateId());
        tier.setThresholdPoints(request.thresholdPoints());
        tier.setCouponTemplateId(template.getId());
        tier.setCouponCount(request.couponCount() == null ? tier.getCouponCount() : request.couponCount());
        tier.setSortOrder(request.sortOrder() == null ? tier.getSortOrder() : request.sortOrder());
        tier.setStatus(request.status() == null ? tier.getStatus() : request.status());
        ladderTierMapper.updateById(tier);
        return PrivilegeViews.toLadderTierView(tier, template);
    }

    // ---------------------------------------------------------------- 内部

    /** 规则设置：单行表，缺行时补一行（迁移里已插，这里兜底）。 */
    PointConfig config() {
        PointConfig config = configMapper.selectById(PointConfig.SINGLETON_ID);
        if (config == null) {
            config = new PointConfig();
            config.setId(PointConfig.SINGLETON_ID);
            config.setDailyEarnLimit(20);
            configMapper.insert(config);
        }
        return config;
    }

    private PointBehavior requireBehavior(String code) {
        PointBehavior behavior = behaviorMapper.selectOne(Wrappers.<PointBehavior>lambdaQuery()
                .eq(PointBehavior::getCode, code));
        if (behavior == null) {
            throw BusinessException.paramInvalid("行为码不存在：" + code + "（新增行为是代码变更）");
        }
        return behavior;
    }

    /**
     * 兑换 / 阶梯只能指向**平台补贴券**（成本归平台，不消耗服务者的贡献额度）。
     *
     * <p>这条校验放在写入口：档位一旦建错，用户兑换时就会把成本记到服务者头上，
     * 而那正是 ADR-0038 第四节明说要避免的事。
     */
    private CouponTemplate requireSubsidyTemplate(Long templateId) {
        CouponTemplate template = templateMapper.selectById(templateId);
        if (template == null || !template.isEnabled()) {
            // **参数错误（40001）而不是冲突（40900）**：这个 id 来自请求体，而它连一个可用的
            // 模板都指不到——「不存在」不是一种冲突状态，冲突的前提是两边都存在。
            // 而下面那条「存在但不是补贴券」才是真正的冲突（模板在，但不适用于这个用途）。
            // 口径见 docs/conventions.md 的「请求体里引用的资源不可用」那一行。
            throw BusinessException.paramInvalid("券模板不存在或已停用");
        }
        if (!template.isPlatformSubsidy()) {
            throw BusinessException.conflict("只能配平台补贴券：兑换与阶梯奖励的成本归平台，不消耗服务者的贡献额度");
        }
        return template;
    }

    private Map<Long, CouponTemplate> templatesOf(List<Long> ids) {
        List<Long> distinct = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, CouponTemplate> map = new HashMap<>();
        if (distinct.isEmpty()) {
            return map;
        }
        for (CouponTemplate template : templateMapper.selectBatchIds(distinct)) {
            map.put(template.getId(), template);
        }
        return map;
    }

    private Map<String, PointBehavior> behaviorsOf(List<PointRecord> records) {
        List<String> codes = records.stream().map(PointRecord::getBehaviorCode).distinct().toList();
        Map<String, PointBehavior> map = new HashMap<>();
        if (codes.isEmpty()) {
            return map;
        }
        for (PointBehavior behavior : behaviorMapper.selectList(Wrappers.<PointBehavior>lambdaQuery()
                .in(PointBehavior::getCode, codes))) {
            map.put(behavior.getCode(), behavior);
        }
        return map;
    }
}
