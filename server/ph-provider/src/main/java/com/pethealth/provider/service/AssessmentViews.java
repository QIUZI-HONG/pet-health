package com.pethealth.provider.service;

import com.pethealth.api.provider.AssessmentDtos;
import com.pethealth.provider.domain.AssessmentItemScore;
import com.pethealth.provider.domain.AssessmentItems;
import com.pethealth.provider.domain.AssessmentLevelRule;
import com.pethealth.provider.domain.AssessmentMonthlyScore;
import com.pethealth.provider.domain.AssessmentOverrideLog;
import com.pethealth.provider.domain.AssessmentRule;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

/**
 * 考核实体 → 契约视图的映射。**只在这一个类里做**（与 {@code ProviderViews} 同一纪律）：
 *
 * <ul>
 *   <li><b>两位小数的字符串只有一处</b>：分数在库里是 DECIMAL，出接口是字符串
 *       （契约写的是「两位小数字符串」）。散在几处早晚会出现「这一项补零、那一项没补」；
 *   <li><b>「未参与」→ {@code null} 只有一处</b>：未参与时分数是空、{@code participated=false}，
 *       不能变成 {@code "0.00"}——那两件事在考核里必须分开（ADR-0050 第四节）；
 *   <li><b>项目顺序只有一处</b>：三项主项在前、过程子项按定义顺序在后。顺序写在这里，
 *       查询不用带 ORDER BY（明细本来就不多）。
 * </ul>
 */
final class AssessmentViews {

    private AssessmentViews() {
    }

    static AssessmentDtos.AssessmentSummaryView toSummary(AssessmentMonthlyScore score, String providerName) {
        return new AssessmentDtos.AssessmentSummaryView(
                score.getId(),
                score.getProviderId(),
                providerName,
                score.getPeriod(),
                plain(score.getTotalScore()),
                score.getLevel(),
                score.getLevelName(),
                score.getRecommendPriority(),
                score.getParticipatedWeight(),
                score.hasOverride(),
                score.getCalculatedAt());
    }

    static AssessmentDtos.AssessmentItemView toItem(AssessmentItemScore item) {
        return new AssessmentDtos.AssessmentItemView(
                item.getItemCode(),
                item.getItemName(),
                item.getParentCode(),
                item.getWeight(),
                item.hasParticipated(),
                plain(item.getScore()),
                plain(item.getCalculatedScore()),
                item.getRawValue(),
                item.getTargetValue(),
                item.getDataSource(),
                item.getNote(),
                item.hasOverride());
    }

    static AssessmentDtos.AssessmentOverrideView toOverride(AssessmentOverrideLog log) {
        return new AssessmentDtos.AssessmentOverrideView(
                log.getId(),
                log.getItemCode(),
                log.getItemName(),
                plain(log.getBeforeScore()),
                plain(log.getAfterScore()),
                log.getReason(),
                log.getCreatedBy(),
                log.getCreatedAt());
    }

    static AssessmentDtos.AssessmentView toView(AssessmentMonthlyScore score, String providerName,
                                                List<AssessmentItemScore> items,
                                                List<AssessmentOverrideLog> overrides) {
        return new AssessmentDtos.AssessmentView(
                score.getId(),
                score.getProviderId(),
                providerName,
                score.getPeriod(),
                plain(score.getTotalScore()),
                score.getLevel(),
                score.getLevelName(),
                score.getRecommendPriority(),
                score.getParticipatedWeight(),
                score.hasOverride(),
                score.getCalculatedAt(),
                sorted(items).stream().map(AssessmentViews::toItem).toList(),
                overrides.stream().map(AssessmentViews::toOverride).toList());
    }

    static AssessmentDtos.AssessmentRuleView toRuleView(AssessmentRule rule, List<AssessmentLevelRule> levels) {
        return new AssessmentDtos.AssessmentRuleView(
                rule.getInviteWeight(),
                rule.getCouponWeight(),
                rule.getProcessWeight(),
                rule.getInviteTarget(),
                plain(rule.getCouponTarget()),
                rule.getResponseMinutesTarget(),
                levels.stream().map(AssessmentViews::toLevelView).toList(),
                rule.getUpdatedAt());
    }

    static AssessmentDtos.AssessmentLevelRuleView toLevelView(AssessmentLevelRule level) {
        return new AssessmentDtos.AssessmentLevelRuleView(
                level.getLevel(),
                level.getLevelName(),
                plain(level.getMinScore()),
                level.getRecommendPriority(),
                level.getUpdatedAt());
    }

    /** 三项主项 → 过程 → 过程子项（明细的阅读顺序）。 */
    private static List<AssessmentItemScore> sorted(List<AssessmentItemScore> items) {
        return items.stream()
                .sorted(Comparator.comparingInt(item -> order(item.getItemCode())))
                .toList();
    }

    private static int order(String code) {
        List<String> order = List.of(
                AssessmentItems.INVITE.code(),
                AssessmentItems.COUPON.code(),
                AssessmentItems.PROCESS.code(),
                AssessmentItems.PROCESS_RESPONSE.code(),
                AssessmentItems.PROCESS_REDEEM_RATE.code(),
                AssessmentItems.PROCESS_REPORT_RATE.code(),
                AssessmentItems.PROCESS_REVIEW.code(),
                AssessmentItems.PROCESS_CANCEL_RATE.code());
        int index = order.indexOf(code);
        return index < 0 ? order.size() : index;
    }

    /** DECIMAL → 两位小数字符串；{@code null} 原样返回（未参与 / 覆盖前无值）。 */
    static String plain(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
