package com.pethealth.account.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

/**
 * 用户（宠物主人）。
 *
 * <p>手机号不存明文：{@code phoneEnc} 是密文、{@code phoneHash} 是 HMAC，等值查询与唯一约束都走后者（ADR-0013）。
 * 明文只在注册、登录、导出这几条路径上短暂存在于内存。
 */
@TableName("user")
public class User extends BaseEntity {

    /** 账号正常。 */
    public static final int STATUS_ACTIVE = 1;
    /** 账号被禁用（风控或运营处置）。 */
    public static final int STATUS_DISABLED = 2;

    /** 注销时间；NULL=未注销（迁移 V9）。 */
    private java.time.LocalDateTime deactivatedAt;

    private String phoneEnc;
    private String phoneHash;
    private String passwordHash;
    private String nickname;

    /** 头像 URL。更新资料时空串表示清空，所以 null 也要写进 UPDATE。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String avatar;
    private Integer gender;
    private Long activePetId;
    private Integer status;

    public String getPhoneEnc() {
        return phoneEnc;
    }

    public void setPhoneEnc(String phoneEnc) {
        this.phoneEnc = phoneEnc;
    }

    public String getPhoneHash() {
        return phoneHash;
    }

    public void setPhoneHash(String phoneHash) {
        this.phoneHash = phoneHash;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public Integer getGender() {
        return gender;
    }

    public void setGender(Integer gender) {
        this.gender = gender;
    }

    public Long getActivePetId() {
        return activePetId;
    }

    public void setActivePetId(Long activePetId) {
        this.activePetId = activePetId;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public boolean isActive() {
        return status == null || status == STATUS_ACTIVE;
    }

    public java.time.LocalDateTime getDeactivatedAt() {
        return deactivatedAt;
    }

    public void setDeactivatedAt(java.time.LocalDateTime deactivatedAt) {
        this.deactivatedAt = deactivatedAt;
    }
}
