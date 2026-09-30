package com.pethealth.privilege.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

import java.time.LocalDateTime;

/**
 * 邀请关系，表 {@code invite_relation}——一次归因 + 一次结算。
 *
 * <p>状态机（三态）：
 *
 * <pre>
 *   注册（归因）→ 1 待生效 →（建档 + 24 小时内有行为）→ 2 有效
 *                          ↘ 反作弊命中 / 24 小时无行为 → 3 无效（不发券、不计数）
 * </pre>
 *
 * <p>为什么把「待生效」显式建出来：有效邀请要等 24 小时观察窗，期间它既不是有效也不是无效。
 * 如果注册即算有效，被邀请人同一批刷出来的号当晚就把券领走了（ADR-0039 的反作弊第三层）。
 *
 * <p>{@code inviteeUserId} 唯一：一个被邀请人一辈子只被归因一次——
 * 「归因只在注册那一刻，不做事后补填」，补填是刷券的入口。
 */
@TableName("invite_relation")
public class InviteRelation extends BaseEntity {

    public static final int CHANNEL_LINK = 1;
    public static final int CHANNEL_FORM = 2;

    public static final int STATUS_PENDING = 1;
    public static final int STATUS_EFFECTIVE = 2;
    public static final int STATUS_INVALID = 3;

    /** 自邀自：被邀请人与邀请人是同一个账号。 */
    public static final String REJECT_SELF = "SELF_INVITE";
    /** 同设备：被邀请人注册设备与该邀请码创建时的设备相同。 */
    public static final String REJECT_SAME_DEVICE = "SAME_DEVICE";
    /** 同 IP + 同号段：同一出口 IP 下、手机号前 7 位相同。 */
    public static final String REJECT_SAME_IP_SEGMENT = "SAME_IP_SEGMENT";
    /** 24 小时内无行为（第二层反作弊的「无行为不发券」）。 */
    public static final String REJECT_NO_ACTIVITY = "NO_ACTIVITY_24H";

    /** 观察窗长度（小时）：ADR-0039 的「被邀请人 24 小时内无行为则不发券」。 */
    public static final int OBSERVE_HOURS = 24;

    private Long inviterUserId;
    private Long inviteeUserId;
    private String inviteCode;
    private Integer channel;
    private Integer status;
    private String rejectReason;
    /** 归因时间 = 注册那一刻；之后不再改。 */
    private LocalDateTime attributedAt;
    private LocalDateTime profileCompletedAt;
    private LocalDateTime settledAt;

    public Long getInviterUserId() {
        return inviterUserId;
    }

    public void setInviterUserId(Long inviterUserId) {
        this.inviterUserId = inviterUserId;
    }

    public Long getInviteeUserId() {
        return inviteeUserId;
    }

    public void setInviteeUserId(Long inviteeUserId) {
        this.inviteeUserId = inviteeUserId;
    }

    public String getInviteCode() {
        return inviteCode;
    }

    public void setInviteCode(String inviteCode) {
        this.inviteCode = inviteCode;
    }

    public Integer getChannel() {
        return channel;
    }

    public void setChannel(Integer channel) {
        this.channel = channel;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public void setRejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
    }

    public LocalDateTime getAttributedAt() {
        return attributedAt;
    }

    public void setAttributedAt(LocalDateTime attributedAt) {
        this.attributedAt = attributedAt;
    }

    public LocalDateTime getProfileCompletedAt() {
        return profileCompletedAt;
    }

    public void setProfileCompletedAt(LocalDateTime profileCompletedAt) {
        this.profileCompletedAt = profileCompletedAt;
    }

    public LocalDateTime getSettledAt() {
        return settledAt;
    }

    public void setSettledAt(LocalDateTime settledAt) {
        this.settledAt = settledAt;
    }
}
