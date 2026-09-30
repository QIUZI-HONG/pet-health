package com.pethealth.provider.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pethealth.api.provider.AssessmentDtos;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.time.AppTime;
import com.pethealth.provider.domain.AssessmentItemScore;
import com.pethealth.provider.domain.AssessmentItems;
import com.pethealth.provider.domain.AssessmentLevelRule;
import com.pethealth.provider.domain.AssessmentMonthlyScore;
import com.pethealth.provider.domain.AssessmentOverrideLog;
import com.pethealth.provider.domain.AssessmentRule;
import com.pethealth.provider.domain.Provider;
import com.pethealth.provider.mapper.AssessmentItemScoreMapper;
import com.pethealth.provider.mapper.AssessmentLevelRuleMapper;
import com.pethealth.provider.mapper.AssessmentMonthlyScoreMapper;
import com.pethealth.provider.mapper.AssessmentOverrideLogMapper;
import com.pethealth.provider.mapper.AssessmentRuleMapper;
import com.pethealth.provider.mapper.ProviderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务者月度考核（F022）：算分、落库、查询、覆盖。
 *
 * <p>职责边界（三块分开，改哪一块都很清楚）：
 *
 * <ul>
 *   <li><b>取数</b>在 {@link AssessmentFactSource}（跨模块只读接口 + 未接线降级）；
 *   <li><b>算分</b>在 {@link AssessmentCalculator}（纯函数，缺项两种口径都在那里）；
 *   <li><b>本文</b>负责编排与落库：快照规则、按唯一键幂等、覆盖留痕、写回 {@code provider} 的等级。
 * </ul>
 *
 * <h2>落库的形状</h2>
 *
 * <pre>
 *   assessment_monthly_score   一个服务者一个账期一条（唯一键 (provider_id, period) = 幂等键）
 *     └─ assessment_item_score 每项一行（未参与的也落行，score 为 NULL）
 *     └─ assessment_override_log 覆盖留痕（append-only）
 * </pre>
 *
 * <h2>三条不变量（都有测试钉着）</h2>
 *
 * <ol>
 *   <li><b>幂等</b>：同一个月重复触发只留一条。靠唯一键 + 撞键忽略，不靠「先查后插」；
 *   <li><b>覆盖必须留痕</b>：前后值、理由、操作者都进 append-only 的表，且服务者侧看得到；
 *   <li><b>服务者只能看自己的</b>：别人的账期一律 40400（与「不存在」同码）。
 * </ol>
 */
@Service
public class AssessmentService {

    private static final Logger log = LoggerFactory.getLogger(AssessmentService.class);

    private final AssessmentRuleMapper ruleMapper;
    private final AssessmentLevelRuleMapper levelRuleMapper;
    private final AssessmentMonthlyScoreMapper scoreMapper;
    private final AssessmentItemScoreMapper itemMapper;
    private final AssessmentOverrideLogMapper overrideLogMapper;
    private final ProviderMapper providerMapper;
    private final ProviderAccess access;
    private final AssessmentFactSource factSource;
    private final AssessmentSuperAdminGuard superAdminGuard;

    public AssessmentService(AssessmentRuleMapper ruleMapper,
                            AssessmentLevelRuleMapper levelRuleMapper,
                            AssessmentMonthlyScoreMapper scoreMapper,
                            AssessmentItemScoreMapper itemMapper,
                            AssessmentOverrideLogMapper overrideLogMapper,
                            ProviderMapper providerMapper,
                            ProviderAccess access,
                            AssessmentFactSource factSource,
                            AssessmentSuperAdminGuard superAdminGuard) {
        this.ruleMapper = ruleMapper;
        this.levelRuleMapper = levelRuleMapper;
        this.scoreMapper = scoreMapper;
        this.itemMapper = itemMapper;
        this.overrideLogMapper = overrideLogMapper;
        this.providerMapper = providerMapper;
        this.access = access;
        this.factSource = factSource;
        this.superAdminGuard = superAdminGuard;
    }

    // ---------------------------------------------------------------- 服务者侧查询

    /** 我的月度考核列表（账期倒序；可按账期过滤）。技师也能看——权限矩阵里他「看团队」。 */
    @Transactional(readOnly = true)
    public PageResult<AssessmentDtos.AssessmentSummaryView> listMine(long userId, String period,
                                                                     long page, long pageSize) {
        Provider provider = access.requireBound(userId);
        YearMonth parsed = period == null || period.isBlank() ? null : parsePeriod(period);
        Page<AssessmentMonthlyScore> result = scoreMapper.selectPage(new Page<>(page, pageSize),
                Wrappers.<AssessmentMonthlyScore>lambdaQuery()
                        .eq(AssessmentMonthlyScore::getProviderId, provider.getId())
                        .eq(parsed != null, AssessmentMonthlyScore::getPeriod,
                                parsed == null ? null : parsed.toString())
                        .orderByDesc(AssessmentMonthlyScore::getPeriod));
        // 服务者看自己的列表：不带门店名（契约里 provider_id / provider_name 只在运营侧有值）
        return PageResult.from(result, score -> AssessmentViews.toSummary(score, null));
    }

    /** 我的某月明细。**别人的账期与不存在的账期同码**（40400）。 */
    @Transactional(readOnly = true)
    public AssessmentDtos.AssessmentView getMine(long userId, String period) {
        Provider provider = access.requireBound(userId);
        YearMonth parsed = parsePeriod(period);
        AssessmentMonthlyScore score = scoreMapper.selectOne(Wrappers.<AssessmentMonthlyScore>lambdaQuery()
                .eq(AssessmentMonthlyScore::getProviderId, provider.getId())
                .eq(AssessmentMonthlyScore::getPeriod, parsed.toString()));
        if (score == null) {
            throw BusinessException.notFound("该账期还没有考核记录");
        }
        return AssessmentViews.toView(score, null, itemsOf(score.getId()), overridesOf(score.getId()));
    }

    // ---------------------------------------------------------------- 运营侧查询

    /** 全平台考核列表（按账期 / 等级 / 门店名筛选，账期倒序）。 */
    @Transactional(readOnly = true)
    public PageResult<AssessmentDtos.AssessmentSummaryView> listAll(String period, Integer level, String keyword,
                                                                    long page, long pageSize) {
        YearMonth parsed = period == null || period.isBlank() ? null : parsePeriod(period);
        String name = keyword == null ? null : keyword.trim();

        Collection<Long> providerIds = null;
        if (name != null && !name.isEmpty()) {
            List<Long> matched = providerMapper.selectList(Wrappers.<Provider>lambdaQuery()
                            .like(Provider::getName, name))
                    .stream().map(Provider::getId).toList();
            if (matched.isEmpty()) {
                return PageResult.of(List.of(), page, pageSize, 0);
            }
            providerIds = matched;
        }
        Page<AssessmentMonthlyScore> result = scoreMapper.selectPage(new Page<>(page, pageSize),
                Wrappers.<AssessmentMonthlyScore>lambdaQuery()
                        .eq(parsed != null, AssessmentMonthlyScore::getPeriod,
                                parsed == null ? null : parsed.toString())
                        .eq(level != null, AssessmentMonthlyScore::getLevel, level)
                        .in(providerIds != null, AssessmentMonthlyScore::getProviderId, providerIds)
                        .orderByDesc(AssessmentMonthlyScore::getPeriod)
                        .orderByDesc(AssessmentMonthlyScore::getTotalScore));
        Map<Long, String> names = providerNames(result.getRecords().stream()
                .map(AssessmentMonthlyScore::getProviderId).distinct().toList());
        return PageResult.from(result, score -> AssessmentViews.toSummary(score,
                names.get(score.getProviderId())));
    }

    /** 运营看任意一条（服务者侧的同形状接口只能看自己的）。 */
    @Transactional(readOnly = true)
    public AssessmentDtos.AssessmentView get(long scoreId) {
        AssessmentMonthlyScore score = requireScore(scoreId);
        Map<Long, String> names = providerNames(List.of(score.getProviderId()));
        return AssessmentViews.toView(score, names.get(score.getProviderId()),
                itemsOf(score.getId()), overridesOf(score.getId()));
    }

    // ---------------------------------------------------------------- 规则

    /** 当前的考核规则（含三档阈值）。运营后台可读——规则要能被看见，否则分数没法解释。 */
    @Transactional(readOnly = true)
    public AssessmentDtos.AssessmentRuleView ruleView() {
        return AssessmentViews.toRuleView(requireRule(), levelRules());
    }

    /**
     * 整体覆盖考核规则（**只归超级管理员**）。
     *
     * <p>三条校验：权重之和必须 100（否则总分的量纲没有意义）、恰好三档且阈值严格递增、
     * 基础档从 0.00 起（否则会出现「谁都不匹配」的分数段）。
     *
     * <p>改完**只影响之后算出的账期**：历史账期的权重与达标线是快照（V35），
     * 所以「跨月可对比」不受影响。
     */
    @Transactional
    public AssessmentDtos.AssessmentRuleView updateRule(AssessmentDtos.AssessmentRuleRequest request) {
        superAdminGuard.requireSuperAdmin();
        if (request.inviteWeight() + request.couponWeight() + request.processWeight() != 100) {
            throw BusinessException.paramInvalid("三项权重之和必须是 100（当前 "
                    + (request.inviteWeight() + request.couponWeight() + request.processWeight()) + "）");
        }
        List<AssessmentDtos.AssessmentLevelRuleRequest> levels = request.levels();
        validateLevels(levels);

        AssessmentRule rule = requireRule();
        rule.setInviteWeight(request.inviteWeight());
        rule.setCouponWeight(request.couponWeight());
        rule.setProcessWeight(request.processWeight());
        rule.setInviteTarget(request.inviteTarget());
        rule.setCouponTarget(new BigDecimal(request.couponTarget()));
        rule.setResponseMinutesTarget(request.responseMinutesTarget());
        ruleMapper.updateById(rule);

        for (AssessmentDtos.AssessmentLevelRuleRequest levelRequest : levels) {
            AssessmentLevelRule levelRule = levelRuleMapper.selectOne(
                    Wrappers.<AssessmentLevelRule>lambdaQuery()
                            .eq(AssessmentLevelRule::getLevel, levelRequest.level()));
            if (levelRule == null) {
                throw BusinessException.paramInvalid("等级 " + levelRequest.level() + " 的档位不存在");
            }
            levelRule.setMinScore(new BigDecimal(levelRequest.minScore()));
            levelRule.setRecommendPriority(levelRequest.recommendPriority());
            levelRuleMapper.updateById(levelRule);
        }
        return AssessmentViews.toRuleView(requireRule(), levelRules());
    }

    /**
     * 校验三档阈值：恰好三档、等级不重复、阈值**严格递增**、基础档必须是 0.00（否则会出现
     * 「谁都匹配不上」的分数段）。
     *
     * <p>比较一律走 {@link BigDecimal#compareTo}：{@code 2.0} 与 {@code 2.00} 数值相等而
     * {@code equals} 为 false，用后者会把「阈值写平了」这种该拦的输入放过去。
     * 先按等级排序再比，所以请求里的档位顺序不影响结论。
     */
    private void validateLevels(List<AssessmentDtos.AssessmentLevelRuleRequest> levels) {
        if (levels.size() != AssessmentLevelRule.LEVEL_COUNT) {
            throw BusinessException.paramInvalid("档位必须恰好三档（1 基础 / 2 优选 / 3 战略合作）");
        }
        List<Integer> seen = new ArrayList<>();
        BigDecimal previous = null;
        List<AssessmentDtos.AssessmentLevelRuleRequest> sorted = levels.stream()
                .sorted(java.util.Comparator.comparing(AssessmentDtos.AssessmentLevelRuleRequest::level))
                .toList();
        for (AssessmentDtos.AssessmentLevelRuleRequest level : sorted) {
            if (seen.contains(level.level())) {
                throw BusinessException.paramInvalid("等级 " + level.level() + " 给了不止一次");
            }
            seen.add(level.level());
            BigDecimal min = new BigDecimal(level.minScore());
            if (previous != null && min.compareTo(previous) <= 0) {
                throw BusinessException.paramInvalid("档位阈值必须严格递增（等级 " + level.level()
                        + " 的阈值不大于上一档）");
            }
            previous = min;
        }
        if (new BigDecimal(sorted.get(0).minScore()).compareTo(BigDecimal.ZERO) != 0) {
            throw BusinessException.paramInvalid("基础档的阈值必须是 0.00（否则会出现谁都匹配不上的分数段）");
        }
    }

    // ---------------------------------------------------------------- 算分

    /**
     * 算一个服务者一个账期并落库。**幂等**：已有那一期就直接返回 {@code false}。
     *
     * @param period 账期（yyyy-MM）
     * @return true 表示这次真的算并写入了；false 表示这一期已经算过（重复触发）
     */
    @Transactional
    public boolean calculate(long providerId, String period) {
        YearMonth month = parsePeriod(period);
        AssessmentMonthlyScore existing = scoreMapper.selectOne(
                Wrappers.<AssessmentMonthlyScore>lambdaQuery()
                        .eq(AssessmentMonthlyScore::getProviderId, providerId)
                        .eq(AssessmentMonthlyScore::getPeriod, month.toString()));
        if (existing != null) {
            return false;
        }
        AssessmentRule rule = requireRule();
        if (!rule.weightsSumTo100()) {
            // 规则被手工改坏时**不算分**：算出来的总分是没有量纲的，比不落这一期更坏
            log.error("考核规则的三项权重之和不是 100，跳过 providerId={} period={}（请先在运营后台修正规则）",
                    providerId, period);
            return false;
        }
        List<AssessmentLevelRule> levels = levelRules();
        AssessmentFacts facts = factSource.collect(providerId, month.atDay(1), month.atEndOfMonth());
        AssessmentCalculator.Outcome outcome = AssessmentCalculator.evaluate(rule, facts);

        AssessmentMonthlyScore score = newScore(providerId, month, rule, outcome, levels);
        try {
            scoreMapper.insert(score);
        } catch (DuplicateKeyException e) {
            // 并发下同一个账期：唯一键兜底（多实例同时跑、或运营手动补跑撞上批算）
            log.info("考核分并发写入冲突，以先到的为准：providerId={} period={}", providerId, period);
            return false;
        }
        for (AssessmentCalculator.ItemResult item : outcome.items()) {
            itemMapper.insert(newItem(score, rule, item));
        }
        writeBackToProvider(providerId, month.toString(), score);
        return true;
    }

    /** 批算一个账期的**全部正常服务者**（{@link AssessmentMonthlyJob} 调它；测试也直接调）。 */
    @Transactional
    public int calculateMonth(YearMonth month) {
        List<Provider> providers = providerMapper.selectList(Wrappers.<Provider>lambdaQuery()
                .eq(Provider::getStatus, Provider.STATUS_APPROVED));
        int calculated = 0;
        for (Provider provider : providers) {
            if (calculate(provider.getId(), month.toString())) {
                calculated++;
            }
        }
        return calculated;
    }

    /** 组装落库行。三项**权重随行快照**（V35 的 invite/coupon/process_weight），达标线快照在明细的
     *  {@code target_value} 上——两处合起来才是「跨月可对比」。参与标记落 tinyint，等级按总分定。 */
    private AssessmentMonthlyScore newScore(long providerId, YearMonth month, AssessmentRule rule,
                                            AssessmentCalculator.Outcome outcome,
                                            List<AssessmentLevelRule> levels) {
        AssessmentLevelRule level = AssessmentCalculator.levelOf(outcome.totalScore(), levels);
        AssessmentMonthlyScore score = new AssessmentMonthlyScore();
        score.setProviderId(providerId);
        score.setPeriod(month.toString());
        score.setInviteWeight(AssessmentCalculator.weightOf(rule, AssessmentItems.INVITE.code()));
        score.setCouponWeight(AssessmentCalculator.weightOf(rule, AssessmentItems.COUPON.code()));
        score.setProcessWeight(AssessmentCalculator.weightOf(rule, AssessmentItems.PROCESS.code()));
        score.setInviteScore(outcome.scoreOf(AssessmentItems.INVITE.code()));
        score.setCouponScore(outcome.scoreOf(AssessmentItems.COUPON.code()));
        score.setProcessScore(outcome.scoreOf(AssessmentItems.PROCESS.code()));
        score.setInviteParticipated(flag(outcome.participated(AssessmentItems.INVITE.code())));
        score.setCouponParticipated(flag(outcome.participated(AssessmentItems.COUPON.code())));
        score.setProcessParticipated(flag(outcome.participated(AssessmentItems.PROCESS.code())));
        score.setParticipatedWeight(outcome.participatedWeight());
        score.setTotalScore(outcome.totalScore());
        applyLevel(score, level);
        score.setOverridden(0);
        score.setCalculatedAt(AppTime.now());
        return score;
    }

    /** 组装明细行。**未参与的项目也落行**（{@code score} 为 NULL、{@code participated} = 0）：
     *  「未参与」与「0 分」是两件事，前端要能逐项显示原因（ADR-0050 第四节）。
     *  {@code weight} 只给三项主项，过程子项留 null（子项等权，不参与加权）。 */
    private AssessmentItemScore newItem(AssessmentMonthlyScore score, AssessmentRule rule,
                                        AssessmentCalculator.ItemResult result) {
        AssessmentItemScore item = new AssessmentItemScore();
        item.setScoreId(score.getId());
        item.setProviderId(score.getProviderId());
        item.setPeriod(score.getPeriod());
        item.setItemCode(result.item().code());
        item.setItemName(result.item().name());
        item.setParentCode(result.item().parentCode());
        item.setWeight(result.item().isMain()
                ? AssessmentCalculator.weightOf(rule, result.item().code())
                : null);
        item.setParticipated(flag(result.participated()));
        item.setScore(result.score());
        item.setCalculatedScore(result.score());
        item.setRawValue(result.rawValue());
        item.setTargetValue(result.targetValue());
        item.setDataSource(result.dataSource());
        item.setNote(result.note());
        item.setOverridden(0);
        return item;
    }

    /** 参与标记 → 落库的 tinyint（列是 NOT NULL 的 0/1，别直接塞 boolean）。 */
    private static int flag(boolean value) {
        return value ? AssessmentMonthlyScore.PARTICIPATED : AssessmentMonthlyScore.NOT_PARTICIPATED;
    }

    // ---------------------------------------------------------------- 覆盖单项分

    /**
     * 覆盖单项分（**只归超级管理员**）。ADR-0039 第三节：谁、何时、理由、覆盖前后值都留。
     *
     * <p>四步，缺一步这条口径就不成立：
     *
     * <ol>
     *   <li>门禁：{@link AssessmentSuperAdminGuard}（还有一层「只能覆盖三项主项」的业务规则）；
     *   <li>留痕：往 {@code assessment_override_log} 追加一条（before = 当时的生效值，可为空）；
     *   <li>改生效值：明细的 {@code score} 换成覆盖值（{@code calculated_score} **不动**，
     *       它是「还原」与申诉的依据），并标记 {@code overridden}；
     *   <li>重算总分与等级并写回 {@code provider} —— 否则「总分与明细对不上」，而总分是
     *       服务者与推荐侧真正在用的那个数。
     * </ol>
     */
    @Transactional
    public AssessmentDtos.AssessmentView override(long scoreId, AssessmentDtos.AssessmentOverrideRequest request) {
        long operatorId = superAdminGuard.requireSuperAdmin();
        AssessmentMonthlyScore score = requireScore(scoreId);
        String itemCode = request.itemCode().trim().toUpperCase(java.util.Locale.ROOT);
        if (!AssessmentItems.overridable(itemCode)) {
            throw BusinessException.paramInvalid("只能覆盖三项主项（INVITE 拉新 / COUPON 券 / PROCESS 过程）："
                    + "过程子项是过程分的构成，覆盖它们等于绕过算法");
        }
        BigDecimal after = new BigDecimal(request.score());
        if (after.compareTo(BigDecimal.ZERO) < 0 || after.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw BusinessException.paramInvalid("覆盖后的得分必须在 0 到 100 之间");
        }
        AssessmentItemScore item = itemMapper.selectOne(Wrappers.<AssessmentItemScore>lambdaQuery()
                .eq(AssessmentItemScore::getScoreId, score.getId())
                .eq(AssessmentItemScore::getItemCode, itemCode));
        if (item == null) {
            throw BusinessException.notFound("该考核记录里没有这一项");
        }
        BigDecimal before = item.getScore();

        item.setScore(after);
        item.setParticipated(AssessmentMonthlyScore.PARTICIPATED);
        item.setOverridden(1);
        String stamp = "被超级管理员覆盖为 " + AssessmentViews.plain(after) + "（理由见覆盖留痕）";
        item.setNote(item.getNote() == null || item.getNote().isBlank() ? stamp : item.getNote() + "；" + stamp);
        itemMapper.updateById(item);

        AssessmentOverrideLog logRow = new AssessmentOverrideLog();
        logRow.setScoreId(score.getId());
        logRow.setProviderId(score.getProviderId());
        logRow.setPeriod(score.getPeriod());
        logRow.setItemCode(itemCode);
        logRow.setItemName(item.getItemName());
        logRow.setBeforeScore(before);
        logRow.setAfterScore(after);
        logRow.setReason(request.reason().trim());
        overrideLogMapper.insert(logRow);

        recalculate(score, operatorId);
        Map<Long, String> names = providerNames(List.of(score.getProviderId()));
        return AssessmentViews.toView(score, names.get(score.getProviderId()),
                itemsOf(score.getId()), overridesOf(score.getId()));
    }

    /** 覆盖之后重算总分与等级（用明细里的权重快照，见 {@code AssessmentCalculator.recalcTotal}）。 */
    private void recalculate(AssessmentMonthlyScore score, long operatorId) {
        List<AssessmentItemScore> items = itemsOf(score.getId());
        BigDecimal total = AssessmentCalculator.recalcTotal(items);
        score.setInviteScore(itemScore(items, AssessmentItems.INVITE.code()));
        score.setCouponScore(itemScore(items, AssessmentItems.COUPON.code()));
        score.setProcessScore(itemScore(items, AssessmentItems.PROCESS.code()));
        score.setParticipatedWeight(AssessmentCalculator.recalcParticipatedWeight(items));
        score.setTotalScore(total);
        // 等级按**当前生效**的分档阈值算：覆盖是当下的运营动作，抬到哪一档要看现在的规则
        applyLevel(score, AssessmentCalculator.levelOf(total, levelRules()));
        score.setOverridden(1);
        scoreMapper.updateById(score);
        log.info("考核单项分被覆盖：scoreId={} period={} operatorId={} 重算后总分={}",
                score.getId(), score.getPeriod(), operatorId, score.getTotalScore());
        writeBackToProvider(score.getProviderId(), score.getPeriod(), score);
    }

    /** 取某项的**生效**得分（被覆盖后就是覆盖值；算法原值在 {@code calculatedScore}）。
     *  缺行或未参与时为 null——与 {@code AssessmentCalculator.Outcome.scoreOf} 同一口径。 */
    private static BigDecimal itemScore(List<AssessmentItemScore> items, String code) {
        // 与 AssessmentCalculator.Outcome.scoreOf 同一个坑：得分可能是 null（未参与），
        // 用 map + findFirst 会变成一次 NPE
        for (AssessmentItemScore item : items) {
            if (item.getItemCode().equals(code)) {
                return item.getScore();
            }
        }
        return null;
    }

    /** 等级三列一起写（等级 / 中文名 / 推荐优先级）：分开写会出现「等级是优选、优先度还是基础」这种
     *  自相矛盾的行。没有匹配到档位时保守给基础档——规则不全不报错，但也不白送高分。 */
    private static void applyLevel(AssessmentMonthlyScore score, AssessmentLevelRule level) {
        score.setLevel(level == null ? AssessmentLevelRule.LEVEL_BASIC : level.getLevel());
        score.setLevelName(level == null ? "基础" : level.getLevelName());
        score.setRecommendPriority(level == null ? 3 : level.getRecommendPriority());
    }

    /**
     * 把最近一期的等级、总分与推荐优先级写回 {@code provider}（推荐侧与 C 端读的是这三列）。
     *
     * <p>**只写「不比库里更新的那一期更旧」的账期**：手动补算一个历史账期时，不该把门店的等级
     * 退回几个月前——那是看得见的错。判定用的是分表里的最大账期（同一个服务者），
     * 所以补算历史不会改这几列。
     *
     * <p><b>推荐优先级必须一起写回</b>：C 端找店排序读的就是 {@code provider.recommend_priority}，
     * 而它的唯一来源是这里。少了这一行，那一列只会在 V45 的回填里被写一次，之后再也不会变——
     * 「等级决定 AI 推荐优先级」（交付文档 F022）就是断的，而且比改造前更难查
     * （改造前读的至少是会随考核变的 {@code level}）。
     */
    private void writeBackToProvider(long providerId, String period, AssessmentMonthlyScore score) {
        String latest = scoreMapper.selectOne(Wrappers.<AssessmentMonthlyScore>lambdaQuery()
                        .eq(AssessmentMonthlyScore::getProviderId, providerId)
                        .orderByDesc(AssessmentMonthlyScore::getPeriod)
                        .last("LIMIT 1"))
                .getPeriod();
        if (latest != null && latest.compareTo(period) > 0) {
            return; // 库里已有更新的账期：不倒退
        }
        Provider provider = providerMapper.selectById(providerId);
        if (provider == null) {
            return;
        }
        provider.setLevel(score.getLevel());
        provider.setMonthlyScore(score.getTotalScore());
        // 档位表的快照（applyLevel 已按映射算进分表）——列非空，所以空值时不覆盖，别把 NPE 写成一次约束冲突
        if (score.getRecommendPriority() != null) {
            provider.setRecommendPriority(score.getRecommendPriority());
        }
        providerMapper.updateById(provider);
    }

    // ---------------------------------------------------------------- 取行与工具

    /** 取一条账期。不存在即 40400；「不是你的」由服务者侧的归属过滤（{@code getMine}）先挡掉，
     *  两种情况的答复没有区别。 */
    private AssessmentMonthlyScore requireScore(long scoreId) {
        AssessmentMonthlyScore score = scoreMapper.selectById(scoreId);
        if (score == null) {
            throw BusinessException.notFound("考核记录不存在");
        }
        return score;
    }

    /** 一次取回该账期的全部明细（含未参与的子项行）：详情、覆盖重算、写回都基于它，别在循环里逐项查。 */
    private List<AssessmentItemScore> itemsOf(long scoreId) {
        return itemMapper.selectList(Wrappers.<AssessmentItemScore>lambdaQuery()
                .eq(AssessmentItemScore::getScoreId, scoreId));
    }

    /** 覆盖留痕：按 id 升序 = 实际发生的先后（append-only 的流水，页面按时间线读）。 */
    private List<AssessmentOverrideLog> overridesOf(long scoreId) {
        return overrideLogMapper.selectList(Wrappers.<AssessmentOverrideLog>lambdaQuery()
                .eq(AssessmentOverrideLog::getScoreId, scoreId)
                .orderByAsc(AssessmentOverrideLog::getId));
    }

    /** 批量取服务者名（一次 in 查询）：运营列表每行都要带服务者名，逐行查就是 N+1；查不到的 id 不在 map 里。 */
    private Map<Long, String> providerNames(List<Long> providerIds) {
        Map<Long, String> names = new HashMap<>();
        if (providerIds.isEmpty()) {
            return names;
        }
        providerMapper.selectBatchIds(providerIds)
                .forEach(provider -> names.put(provider.getId(), provider.getName()));
        return names;
    }

    /** 单行规则；种子里就有它，查不到说明迁移没跑（宁可报错也不拿默认值算分）。 */
    private AssessmentRule requireRule() {
        AssessmentRule rule = ruleMapper.selectById(AssessmentRule.SINGLETON_ID);
        if (rule == null) {
            throw new BusinessException(com.pethealth.common.error.ErrorCode.SERVICE_UNAVAILABLE,
                    "考核规则不存在（迁移 V34 没有执行？）");
        }
        return rule;
    }

    /** 三档阈值，从基础档到战略合作升序（运营后台按这个顺序展示；取档见 {@code AssessmentCalculator.levelOf}）。 */
    private List<AssessmentLevelRule> levelRules() {
        return levelRuleMapper.selectList(Wrappers.<AssessmentLevelRule>lambdaQuery()
                .orderByAsc(AssessmentLevelRule::getLevel));
    }

    /** 解析账期（yyyy-MM）。格式不对 → 40001（不要让 {@code YearMonth.parse} 的异常落成 50000）。 */
    private static YearMonth parsePeriod(String period) {
        if (period == null || period.isBlank()) {
            throw BusinessException.paramInvalid("账期必填，格式 yyyy-MM（如 2026-08）");
        }
        try {
            return YearMonth.parse(period.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw BusinessException.paramInvalid("账期格式不正确，应为 yyyy-MM（如 2026-08）");
        }
    }
}
