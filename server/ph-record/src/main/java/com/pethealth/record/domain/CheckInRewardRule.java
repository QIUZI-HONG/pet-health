package com.pethealth.record.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

/**
 * 打卡连续天数的奖励档位（V41，交付文档 F017 的「打卡得券」）。
 *
 * <p>与 {@code point_ladder_tier}（月度阶梯）、{@code invite_ladder_tier}（邀请阶梯）同一种形状：
 * **档位是一行可停用、可运营改的配置**（ADR-0010 的分层：业务可调项入库）。换一张券、加一档
 * （比如「连续 30 天」）都不该发版，所以它不写成代码常量。
 *
 * <p>为什么这张表在 ph-record：档位的读法只有一处——打卡成功后的判定（{@link
 * com.pethealth.record.service.CheckInRewardListener}），而「连续天数」是档案模块的概念
 * （{@code CheckInService.streak}）。发券动作走 ph-privilege 的接口，本模块不碰 {@code coupon} 表。
 *
 * <p>这一版**只有种子、没有运营页面**（见 V41 的文件注释）：运营要改档位暂时只能写 SQL。
 */
@TableName("check_in_reward_rule")
public class CheckInRewardRule extends BaseEntity {

    public static final int STATUS_ENABLED = 1;

    private Integer streakDays;
    private Long couponTemplateId;
    private Integer couponCount;
    private Integer sortOrder;
    private Integer status;

    public boolean isEnabled() {
        return status != null && status == STATUS_ENABLED;
    }

    public Integer getStreakDays() {
        return streakDays;
    }

    public void setStreakDays(Integer streakDays) {
        this.streakDays = streakDays;
    }

    public Long getCouponTemplateId() {
        return couponTemplateId;
    }

    public void setCouponTemplateId(Long couponTemplateId) {
        this.couponTemplateId = couponTemplateId;
    }

    public Integer getCouponCount() {
        return couponCount;
    }

    public void setCouponCount(Integer couponCount) {
        this.couponCount = couponCount;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
