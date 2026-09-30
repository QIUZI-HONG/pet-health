package com.pethealth.privilege.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

/**
 * 邀请反作弊拦截记录，表 {@code invite_risk_record}。
 *
 * <p>为什么单独一张表而不是打日志：被拦下的邀请要**看得见、查得到、按判据聚合**——
 * 「今天同设备拦了多少」是判断刷量规模的第一步，日志里翻不出来。
 *
 * <p>四条判据（ADR-0039 第一层的三条 + 第三层的一条）写在 {@link InviteRelation} 的常量里，
 * 这里只存命中的那一次。记录里带 {@code deviceId} / {@code ip} 是刻意的：
 * 排查时要靠它串出同一批账号。
 */
@TableName("invite_risk_record")
public class InviteRiskRecord extends BaseEntity {

    private String rule;
    private Long inviterUserId;
    private Long inviteeUserId;
    private String deviceId;
    private String ip;
    private String detail;

    public String getRule() {
        return rule;
    }

    public void setRule(String rule) {
        this.rule = rule;
    }

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

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }
}
