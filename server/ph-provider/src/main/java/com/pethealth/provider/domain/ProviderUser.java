package com.pethealth.provider.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

/**
 * 服务者后台账号与角色的绑定，表 {@code provider_user}。
 *
 * <p>绑定发生在**入驻审核通过的那一刻**：申请人成为该服务者的管理员。之前（待审核 / 驳回）他不属于
 * 任何服务者，只能看自己提交的申请单——这样「审核中」不会出现一个半成品服务者。
 *
 * <p>角色只有两个，且是**包含关系而不是两套体系**（2026-09-29 口径）：
 * {@code 技师 ⊂ 管理员}，同一个人兼任时只留一条 {@code role=1} 的记录。
 * 所以 {@code uk_provider_user(provider_id, user_id)} 是唯一键，不需要「同一个人两条角色记录」。
 *
 * <p>本期的门禁只认 {@code role=1}：选品定价、门店维护、提案都是管理员的动作；
 * 技师的动作范围（接单、报工、照片墙）由 #87 定，本切片不实现。
 */
@TableName("provider_user")
public class ProviderUser extends BaseEntity {

    public static final int ROLE_ADMIN = 1;
    public static final int ROLE_TECHNICIAN = 2;

    public static final int STATUS_ACTIVE = 1;
    public static final int STATUS_DISABLED = 0;

    private Long providerId;
    private Long userId;
    private Integer role;
    private Integer status;

    public boolean isActiveAdmin() {
        return role != null && role == ROLE_ADMIN && status != null && status == STATUS_ACTIVE;
    }

    public Long getProviderId() {
        return providerId;
    }

    public void setProviderId(Long providerId) {
        this.providerId = providerId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Integer getRole() {
        return role;
    }

    public void setRole(Integer role) {
        this.role = role;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
