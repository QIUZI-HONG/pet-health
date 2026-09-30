package com.pethealth.privilege.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

/**
 * 邀请阶梯档位，表 {@code invite_ladder_tier}——**门槛固定五档（1/3/5/10/15），奖励物可配**。
 *
 * <p>为什么奖励物留空还能成立：{@code rewardType} 为空表示「这一档达成时不发东西」，
 * 达成记录照记。发什么券、给哪个权益码，ADR 没定——编一个「满 3 人送 20 元券」
 * 就是替甲方做产品决策（ADR-0046 的待澄清）。
 *
 * <p>「谁在哪一档」是数据，「发什么」是配置：混在一起会让改配置时丢掉历史。
 */
@TableName("invite_ladder_tier")
public class InviteLadderTier extends BaseEntity {

    public static final int REWARD_COUPON = 1;
    public static final int REWARD_RIGHTS = 2;

    public static final int STATUS_ENABLED = 1;
    public static final int STATUS_DISABLED = 0;

    /** 门槛：有效邀请数（ADR-0039 第一节的 1 / 3 / 5 / 10 / 15）。 */
    private Integer threshold;
    private Integer rewardType;
    private Long couponTemplateId;
    private String rightsCode;
    private Integer rewardCount;
    private Integer status;

    public boolean isEnabled() {
        return status != null && status == STATUS_ENABLED;
    }

    /** 这一档配了奖励物没有——没配就只记录达成，不发东西。 */
    public boolean hasReward() {
        return rewardType != null;
    }

    public Integer getThreshold() {
        return threshold;
    }

    public void setThreshold(Integer threshold) {
        this.threshold = threshold;
    }

    public Integer getRewardType() {
        return rewardType;
    }

    public void setRewardType(Integer rewardType) {
        this.rewardType = rewardType;
    }

    public Long getCouponTemplateId() {
        return couponTemplateId;
    }

    public void setCouponTemplateId(Long couponTemplateId) {
        this.couponTemplateId = couponTemplateId;
    }

    public String getRightsCode() {
        return rightsCode;
    }

    public void setRightsCode(String rightsCode) {
        this.rightsCode = rightsCode;
    }

    public Integer getRewardCount() {
        return rewardCount;
    }

    public void setRewardCount(Integer rewardCount) {
        this.rewardCount = rewardCount;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
