package com.pethealth.api.privilege;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 邀请的接口 DTO，与 {@code contract/admin.yaml} 的 {@code Invite*} 组件对齐。
 *
 * <p>三条与 ADR-0039 一一对应的口径：
 *
 * <ul>
 *   <li><b>归因只在注册那一刻</b>：{@link InviteRelationView#attributedAt()} 就是注册时间，
 *       之后不再改。链接带 {@code ?invite=CODE} 只做预填，以用户填的码为准（`channel` 区分两者）；
 *   <li><b>有效邀请 = 被邀请人完成建档 + 24 小时内有行为</b>：所以关系有「待生效」这个中间态，
 *       阶梯计数与积分都按**有效**数算，不按注册数；
 *   <li><b>K 因子退场</b>：换成有效邀请转化率（{@link InviteOverviewView#validRate()}）——
 *       三个端改成 Web 之后没有小程序的社交转发链，K 因子这个指标不成立。
 * </ul>
 */
public final class InviteDtos {

    private InviteDtos() {
    }

    /** 邀请总览。 */
    public record InviteOverviewView(
            Integer inviteCodeCount,
            Integer registeredCount,
            Integer pendingCount,
            Integer effectiveCount,
            Integer invalidCount,
            String validRate,
            List<InviteLadderStatView> ladderStats) {
    }

    /** 某一档的达成人数。 */
    public record InviteLadderStatView(
            Integer threshold,
            Integer achievedCount) {
    }

    /** 一条邀请关系。 */
    public record InviteRelationView(
            Long id,
            Long inviterUserId,
            Long inviteeUserId,
            String inviteCode,
            Integer channel,
            Integer status,
            String rejectReason,
            LocalDateTime attributedAt,
            LocalDateTime profileCompletedAt,
            LocalDateTime settledAt,
            LocalDateTime createdAt) {
    }

    /** 一档阶梯：门槛固定，奖励物可配。 */
    public record InviteLadderTierView(
            Integer threshold,
            Integer rewardType,
            Long couponTemplateId,
            String couponTemplateName,
            String rightsCode,
            String rightsName,
            Integer rewardCount,
            Integer status,
            LocalDateTime updatedAt) {
    }

    /**
     * 配置某一档的奖励。
     *
     * <p>{@code rewardType} 为空表示这一档**不发东西**（达成记录照记）：
     * 发什么券、给哪个权益码，产品没定之前不编——奖励物留空是有效状态，不是缺数据。
     */
    public record InviteLadderTierRequest(
            @Min(value = 1, message = "奖励类型只能是 1 发券 / 2 授权益")
            @Max(value = 2, message = "奖励类型只能是 1 发券 / 2 授权益")
            Integer rewardType,

            Long couponTemplateId,

            @Size(max = 64, message = "权益码最长 64 个字符")
            String rightsCode,

            @Min(value = 1, message = "发券张数至少 1")
            @Max(value = 10, message = "发券张数最多 10")
            Integer rewardCount,

            @NotNull(message = "状态必填")
            @Min(value = 0, message = "状态只能是 1 启用 / 0 停用")
            @Max(value = 1, message = "状态只能是 1 启用 / 0 停用")
            Integer status) {
    }

    /** 一条反作弊拦截记录。 */
    public record InviteRiskRecordView(
            Long id,
            String rule,
            Long inviterUserId,
            Long inviteeUserId,
            String deviceId,
            String ip,
            String detail,
            LocalDateTime createdAt) {
    }
}
