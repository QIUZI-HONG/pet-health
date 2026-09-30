package com.pethealth.privilege.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

/**
 * 邀请码，表 {@code invite_code}——**一人一码**。
 *
 * <p>除了码本身，这里还存了三样「反作弊判据」：码主人的手机号前 7 位（号段）、设备标识与来源 IP。
 * 它们的作用是让「自邀自 / 同设备多账号 / 同 IP 同号段」这三条判据**可判**——
 * 判据要在归因那一刻用到码主人的这些特征，而注册流程手上只有被邀请人的。
 *
 * <p>为什么存号段而不是完整手机号：完整手机号属身份信息，加密与查找列另有一套机制（ADR-0013），
 * 而「同号段」这个判断只需要前 7 位——存更少的信息就够用，就不要多存。
 */
@TableName("invite_code")
public class InviteCode extends BaseEntity {

    private String code;
    private Long userId;
    private Integer channel;
    private String ownerPhoneSegment;
    private String ownerDeviceId;
    private String ownerIp;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Integer getChannel() {
        return channel;
    }

    public void setChannel(Integer channel) {
        this.channel = channel;
    }

    public String getOwnerPhoneSegment() {
        return ownerPhoneSegment;
    }

    public void setOwnerPhoneSegment(String ownerPhoneSegment) {
        this.ownerPhoneSegment = ownerPhoneSegment;
    }

    public String getOwnerDeviceId() {
        return ownerDeviceId;
    }

    public void setOwnerDeviceId(String ownerDeviceId) {
        this.ownerDeviceId = ownerDeviceId;
    }

    public String getOwnerIp() {
        return ownerIp;
    }

    public void setOwnerIp(String ownerIp) {
        this.ownerIp = ownerIp;
    }
}
