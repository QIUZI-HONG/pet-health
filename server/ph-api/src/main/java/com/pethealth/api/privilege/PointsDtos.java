package com.pethealth.api.privilege;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 积分与任务的接口 DTO，与 {@code contract/admin.yaml} 的 {@code Point*} 组件对齐。
 *
 * <p>三条口径：
 *
 * <ul>
 *   <li><b>积分是整数</b>（不是金额、不是小数）：它与券是两套账，只能兑换券，不能兑换现金或提现
 *       （CONTEXT.md 的积分条目）；
 *   <li><b>流水带变动后余额</b>（{@link PointRecordView#balanceAfter()}）：只记变动数的话，
 *       事后永远无法从流水重建余额，客诉时对不上账；
 *   <li><b>任务不额外发分</b>（{@link PointTaskView#points()} 是该行为的分值，供展示）：
 *       同一行为发两次奖励正是 ADR-0038 第四节点名要钉死的那条。
 * </ul>
 */
public final class PointsDtos {

    private PointsDtos() {
    }

    /**
     * 积分总览。
     *
     * <p>字段名与契约（`account_count` / `today_awarded_users`）逐字对齐：DTO 是手写的，
     * 漂移由 {@code ContractDtoDriftTest} 兜（ADR-0047 的配套护栏），所以这里不留近义名。
     */
    public record PointsOverviewView(
            Integer accountCount,
            Integer balanceTotal,
            Integer earnedTotal,
            Integer spentTotal,
            Integer todayEarned,
            Integer todayAwardedUsers,
            Integer dailyEarnLimit) {
    }

    /** 积分规则设置。 */
    public record PointsSettingsView(
            Integer dailyEarnLimit) {
    }

    /** 修改积分规则设置。 */
    public record PointsSettingsRequest(
            @NotNull(message = "每日上限必填")
            @Min(value = 1, message = "每日上限至少 1 分")
            @Max(value = 1000, message = "每日上限最多 1000 分")
            Integer dailyEarnLimit) {
    }

    /** 一条积分流水。 */
    public record PointRecordView(
            Long id,
            Long userId,
            String behaviorCode,
            String behaviorName,
            Integer change,
            Integer balanceAfter,
            Integer countsTowardDailyCap,
            String sourceRef,
            String remark,
            LocalDateTime createdAt) {
    }

    /** 行为分值表的一行。 */
    public record PointBehaviorView(
            String code,
            String name,
            Integer points,
            Integer countsTowardDailyCap,
            Integer dailyCountLimit,
            Integer monthlyCountLimit,
            Integer onceOnly,
            Integer status,
            Integer sortOrder,
            LocalDateTime updatedAt) {
    }

    /** 修改行为分值（**只能改分值 / 频次 / 启停，不能新增行为**）。 */
    public record PointBehaviorRequest(
            @NotNull(message = "分值必填")
            @Min(value = 0, message = "分值不能为负")
            @Max(value = 1000, message = "单次分值最多 1000")
            Integer points,

            @Min(value = 0, message = "是否占每日上限只能是 1 占 / 0 不占")
            @Max(value = 1, message = "是否占每日上限只能是 1 占 / 0 不占")
            Integer countsTowardDailyCap,

            @Min(value = 0, message = "每日次数上限不能为负")
            Integer dailyCountLimit,

            @Min(value = 0, message = "每月次数上限不能为负")
            Integer monthlyCountLimit,

            @Min(value = 0, message = "是否一次性只能是 1 是 / 0 否")
            @Max(value = 1, message = "是否一次性只能是 1 是 / 0 否")
            Integer onceOnly,

            @Min(value = 0, message = "状态只能是 1 启用 / 0 停用")
            @Max(value = 1, message = "状态只能是 1 启用 / 0 停用")
            Integer status) {
    }

    /** 任务清单的一行。 */
    public record PointTaskView(
            Long id,
            String code,
            String name,
            Integer period,
            String behaviorCode,
            String behaviorName,
            Integer targetCount,
            Integer points,
            Integer sortOrder,
            Integer status,
            LocalDateTime updatedAt) {
    }

    /** 新增 / 修改任务（`code` 创建后不可改）。 */
    public record PointTaskRequest(
            @NotBlank(message = "任务编码不能为空")
            @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,31}$", message = "任务编码用大写字母、数字与下划线，如 DAILY_CHECK_IN")
            String code,

            @NotBlank(message = "任务名不能为空")
            @Size(max = 64, message = "任务名最长 64 个字符")
            String name,

            @NotNull(message = "周期必填")
            @Min(value = 1, message = "周期只能是 1 每日 / 2 每周")
            @Max(value = 2, message = "周期只能是 1 每日 / 2 每周")
            Integer period,

            @NotBlank(message = "行为码不能为空")
            String behaviorCode,

            @NotNull(message = "达标次数必填")
            @Min(value = 1, message = "达标次数至少 1")
            @Max(value = 100, message = "达标次数最多 100")
            Integer targetCount,

            Integer sortOrder,

            @Min(value = 0, message = "状态只能是 1 启用 / 0 停用")
            @Max(value = 1, message = "状态只能是 1 启用 / 0 停用")
            Integer status) {
    }

    /** 兑换档位（只兑平台补贴券）。 */
    public record PointExchangeOptionView(
            Long id,
            String name,
            Integer pointsCost,
            Long couponTemplateId,
            String couponTemplateName,
            String couponFaceValue,
            Integer sortOrder,
            Integer status,
            LocalDateTime updatedAt) {
    }

    /** 新增 / 修改兑换档位。 */
    public record PointExchangeOptionRequest(
            @NotBlank(message = "档位名不能为空")
            @Size(max = 64, message = "档位名最长 64 个字符")
            String name,

            @NotNull(message = "消耗积分必填")
            @Min(value = 1, message = "消耗积分至少 1")
            @Max(value = 100000, message = "消耗积分最多 100000")
            Integer pointsCost,

            @NotNull(message = "券模板必填")
            Long couponTemplateId,

            Integer sortOrder,

            @Min(value = 0, message = "状态只能是 1 启用 / 0 停用")
            @Max(value = 1, message = "状态只能是 1 启用 / 0 停用")
            Integer status) {
    }

    /** 月度阶梯档位。 */
    public record PointLadderTierView(
            Long id,
            Integer thresholdPoints,
            Long couponTemplateId,
            String couponTemplateName,
            Integer couponCount,
            Integer sortOrder,
            Integer status,
            LocalDateTime updatedAt) {
    }

    /** 新增 / 修改月度阶梯档位。 */
    public record PointLadderTierRequest(
            @NotNull(message = "门槛积分必填")
            @Min(value = 1, message = "门槛积分至少 1")
            @Max(value = 1000000, message = "门槛积分最多 1000000")
            Integer thresholdPoints,

            @NotNull(message = "券模板必填")
            Long couponTemplateId,

            @Min(value = 1, message = "发券张数至少 1")
            @Max(value = 10, message = "发券张数最多 10")
            Integer couponCount,

            Integer sortOrder,

            @Min(value = 0, message = "状态只能是 1 启用 / 0 停用")
            @Max(value = 1, message = "状态只能是 1 启用 / 0 停用")
            Integer status) {
    }
}
