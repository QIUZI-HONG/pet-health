package com.pethealth.api.provider;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 服务者考核（F022）的接口 DTO，与 {@code contract/provider.yaml} 与
 * {@code contract/admin.yaml} 的 {@code Assessment*} 组件对齐（两份契约各写一遍，
 * 字段逐项一致——同一个后端 DTO）。
 *
 * <p>四条与 ADR 一一对应的口径：
 *
 * <ul>
 *   <li><b>总分 = 拉新 40% + 券 40% + 过程 20%</b>（ADR-0039 第三节）：三项权重入库可配（ADR-0010），
 *       每个账期把当时的权重与达标线**快照**下来——规则改了，历史账期仍解释得清；
 *   <li><b>缺项两种口径</b>（ADR-0050 第四节）：该做而没做 → 记 0 分（`participated=true, score=0.00`）；
 *       平台侧无该维度要求 → 不参与并重算权重（`participated=false, score=null`），
 *       明细里用 {@link AssessmentItemView#note()} 写明是哪一种；
 *   <li><b>分数是字符串</b>（两位小数）：与金额同一条精度纪律——分数属于要写进报表、要被运营
 *       逐个核对的那类数，用浮点会在「86.00 变成 85.99999」这种地方慢慢出事（ADR-0011）；
 *   <li><b>覆盖对服务者可见</b>：{@link AssessmentView#overrides()} 是覆盖留痕，服务者侧同样下发
 *       ——ADR-0039 第三节说得很直白：不留痕、服务者看不到，算法就是黑箱，无法申诉。
 * </ul>
 */
public final class AssessmentDtos {

    private AssessmentDtos() {
    }

    /** 一个账期的**项目编码**。三项主项 + 过程分的五个子项，与 V35 的 {@code item_code} 同一套。 */
    public static final class Item {
        /** 拉新（有效邀请数）。 */
        public static final String INVITE = "INVITE";
        /** 券（完成率 × 核销数）。 */
        public static final String COUPON = "COUPON";
        /** 过程（五个子项的等权合成）。 */
        public static final String PROCESS = "PROCESS";
        /** 过程子项：接单响应时长。 */
        public static final String PROCESS_RESPONSE = "PROCESS_RESPONSE";
        /** 过程子项：核销率。 */
        public static final String PROCESS_REDEEM_RATE = "PROCESS_REDEEM_RATE";
        /** 过程子项：报工完整率。 */
        public static final String PROCESS_REPORT_RATE = "PROCESS_REPORT_RATE";
        /** 过程子项：评价分（**本期恒为未参与**：评价体系未实现，ADR-0039 第二节）。 */
        public static final String PROCESS_REVIEW = "PROCESS_REVIEW";
        /** 过程子项：服务者取消率（ADR-0049 第七节）。 */
        public static final String PROCESS_CANCEL_RATE = "PROCESS_CANCEL_RATE";

        /** 可被超级管理员覆盖的三项主项（过程子项不可覆盖：它们是过程分的构成，不是考核的单项）。 */
        public static final List<String> OVERRIDABLE = List.of(INVITE, COUPON, PROCESS);

        private Item() {
        }
    }

    /**
     * 一个账期的考核结果（列表行）。
     *
     * <p>{@code providerId} / {@code providerName} **只在运营侧有值**（与
     * {@code CouponContributionView.providerName} 同一口径）：服务者看自己的列表不需要它。
     */
    public record AssessmentSummaryView(
            Long id,
            Long providerId,
            String providerName,
            String period,
            String totalScore,
            Integer level,
            String levelName,
            Integer recommendPriority,
            Integer participatedWeight,
            boolean overridden,
            LocalDateTime calculatedAt) {
    }

    /**
     * 一项考核明细。
     *
     * <p>四个字段是「这个分怎么来的」的全部答案，缺一不可：{@code score}（当前生效值）、
     * {@code participated}（这项算不算数）、{@code dataSource}（数从哪来）、
     * {@code note}（未参与的原因 / 覆盖说明）。{@code calculatedScore} 是算法原值——
     * 被覆盖后它与 {@code score} 不同，服务者据此可以要求还原。
     */
    public record AssessmentItemView(
            String itemCode,
            String itemName,
            String parentCode,
            Integer weight,
            boolean participated,
            String score,
            String calculatedScore,
            String rawValue,
            String targetValue,
            String dataSource,
            String note,
            boolean overridden) {
    }

    /** 单项分覆盖的一条留痕（append-only）。 */
    public record AssessmentOverrideView(
            Long id,
            String itemCode,
            String itemName,
            String beforeScore,
            String afterScore,
            String reason,
            Long operatorId,
            LocalDateTime createdAt) {
    }

    /**
     * 一个账期的考核全貌。服务者侧看自己的、运营侧看全平台的（同一个形状，可见范围不同）。
     *
     * <p>{@code items} 里**未参与的项也在**：藏起来就只剩一个说不清的总分。
     * {@code overrides} 没被覆盖过时是空数组（不是 null）。
     */
    public record AssessmentView(
            Long id,
            Long providerId,
            String providerName,
            String period,
            String totalScore,
            Integer level,
            String levelName,
            Integer recommendPriority,
            Integer participatedWeight,
            boolean overridden,
            LocalDateTime calculatedAt,
            List<AssessmentItemView> items,
            List<AssessmentOverrideView> overrides) {
    }

    /** 一个等级的档位（基础 / 优选 / 战略合作）。等级本身固定三档，可改的是阈值与推荐优先级。 */
    public record AssessmentLevelRuleView(
            Integer level,
            String levelName,
            String minScore,
            Integer recommendPriority,
            LocalDateTime updatedAt) {
    }

    /**
     * 考核规则：三项权重 + 三项达标线 + 三档阈值。
     *
     * <p>{@code inviteTarget = 0} 与 {@code couponTarget = "0.00"} 表示**该项还没定要求**：
     * 按 ADR-0050 第四节不参与计分并重算权重，而不是记 0 分。
     */
    public record AssessmentRuleView(
            Integer inviteWeight,
            Integer couponWeight,
            Integer processWeight,
            Integer inviteTarget,
            String couponTarget,
            Integer responseMinutesTarget,
            List<AssessmentLevelRuleView> levels,
            LocalDateTime updatedAt) {
    }

    /** 一个档位的可改部分（等级与名称是代码常量，改不了）。 */
    public record AssessmentLevelRuleRequest(
            @NotNull(message = "等级必填")
            @Min(value = 1, message = "等级只能是 1 基础 / 2 优选 / 3 战略合作")
            @Max(value = 3, message = "等级只能是 1 基础 / 2 优选 / 3 战略合作")
            Integer level,

            @NotBlank(message = "档位阈值必填")
            @Pattern(regexp = "^\\d{1,3}(\\.\\d{1,2})?$", message = "档位阈值须是最多两位小数的非负数字符串")
            String minScore,

            @NotNull(message = "推荐优先级必填")
            @Min(value = 1, message = "推荐优先级只能是 1 最高 / 2 较高 / 3 普通")
            @Max(value = 3, message = "推荐优先级只能是 1 最高 / 2 较高 / 3 普通")
            Integer recommendPriority) {
    }

    /**
     * 整体覆盖考核规则。
     *
     * <p>三条会被服务端拒绝的规则（都是 40001）：权重之和不等于 100、阈值不严格递增或基础档不为
     * 0.00、分数不是两位小数。改了**只影响之后算出的账期**（历史是快照）。
     */
    public record AssessmentRuleRequest(
            @NotNull(message = "拉新项权重必填")
            @Min(value = 0, message = "权重不能为负")
            @Max(value = 100, message = "权重不能超过 100")
            Integer inviteWeight,

            @NotNull(message = "券项权重必填")
            @Min(value = 0, message = "权重不能为负")
            @Max(value = 100, message = "权重不能超过 100")
            Integer couponWeight,

            @NotNull(message = "过程项权重必填")
            @Min(value = 0, message = "权重不能为负")
            @Max(value = 100, message = "权重不能超过 100")
            Integer processWeight,

            @NotNull(message = "拉新达标线必填（0 表示未配置 → 该项不参与）")
            @Min(value = 0, message = "拉新达标线不能为负")
            Integer inviteTarget,

            @NotBlank(message = "券达标线必填（0.00 表示未配置 → 该项不参与）")
            @Pattern(regexp = "^\\d{1,8}(\\.\\d{1,2})?$", message = "券达标线须是最多两位小数的非负数字符串")
            String couponTarget,

            @NotNull(message = "接单响应达标线必填")
            @Min(value = 1, message = "接单响应达标线至少 1 分钟")
            @Max(value = 10080, message = "接单响应达标线最多 10080 分钟（一周）")
            Integer responseMinutesTarget,

            @NotEmpty(message = "三个档位都要给")
            @Size(min = 3, max = 3, message = "档位必须恰好三档（1 基础 / 2 优选 / 3 战略合作）")
            List<AssessmentLevelRuleRequest> levels) {
    }

    /**
     * 覆盖单项分。**只能覆盖三项主项**（INVITE / COUPON / PROCESS）；理由必填、会下发给服务者。
     */
    public record AssessmentOverrideRequest(
            @NotBlank(message = "项目编码必填")
            @Size(max = 32, message = "项目编码最长 32 个字符")
            String itemCode,

            @NotBlank(message = "覆盖后的得分必填")
            @Pattern(regexp = "^\\d{1,3}(\\.\\d{1,2})?$", message = "得分须是最多两位小数的 0–100 数字符串")
            String score,

            @NotBlank(message = "覆盖理由必填")
            @Size(max = 255, message = "覆盖理由最长 255 个字符")
            String reason) {
    }
}
