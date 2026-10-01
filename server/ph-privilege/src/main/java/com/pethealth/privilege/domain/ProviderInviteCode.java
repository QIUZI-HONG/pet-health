package com.pethealth.privilege.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

/**
 * 门店推广码，表 {@code provider_invite_code}——**一店一码**。
 *
 * <p>它是考核「拉新」那一项的入口：交付文档 F022 写的是「店内铺设二维码，用户扫码绑定门店为
 * 拉新推荐人」。扫这个码注册的用户，在 {@code invite_relation} 里落到
 * {@code inviter_provider_id} 上（而不是某个用户），于是「这家店拉来几个人」成为一个可数的数。
 *
 * <p>与用户邀请码（{@link InviteCode}）**不是一张表**：用户码的主人是一个账号、还带反作弊需要的
 * 号段与设备特征；门店码的主人是一家店，判据也不同。合成一张表会让两边各一半字段为空。
 *
 * <p>码长 10（{@code PV} + 8 位）与用户码的 8 位不同——归因那一刻要按码找主人，
 * 长度不同就不会出现「同一个码既是用户码又是门店码」。
 */
@TableName("provider_invite_code")
public class ProviderInviteCode extends BaseEntity {

    public static final int STATUS_DISABLED = 0;

    /** 门店码前缀。改它等于让已印出去的二维码全部失效，别改。 */
    public static final String CODE_PREFIX = "PV";

    /**
     * 码的状态：1 启用（库里只会出现这一个值——`setStatus` 只在取码时写 1）。
     *
     * <p>**归因不看它**：`InviteService` 按码查表就直接建关系，停用的码照样能归因
     * （缺陷计划 D-08 记着这条：`status` 是个死开关，要么接线要么删列）。
     * 当前唯一的读点是服务者侧读自己推广码的视图（`ProviderInviteCodeService#viewOf`），
     * 它把状态原样给前端显示——所以这个常量现在的作用只是「把 1 命名出来」。
     */
    public static final int STATUS_ENABLED = 1;

    private Long providerId;
    private String code;
    private Integer status;

    public Long getProviderId() {
        return providerId;
    }

    public void setProviderId(Long providerId) {
        this.providerId = providerId;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

}
