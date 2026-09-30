package com.pethealth.privilege.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

/**
 * 月度阶梯档位，表 {@code point_ladder_tier}——F018 的骨架：**上月累计积分 → 阶梯发券**。
 *
 * <p>**种子里一行都没有**：门槛值与奖励没有规则依据，不编（ADR-0038 第四节
 * 「先落定时任务骨架、年度大奖留白」）。档位为空时批算照跑、不发任何券——
 * 骨架是通的，运营配好档位即生效。
 *
 * <p>发的券必须是平台补贴券（成本归平台）：阶梯奖励是平台给的，不该消耗服务者的额度。
 */
@TableName("point_ladder_tier")
public class PointLadderTier extends BaseEntity {

    public static final int STATUS_ENABLED = 1;
    public static final int STATUS_DISABLED = 0;

    private Integer thresholdPoints;
    private Long couponTemplateId;
    private Integer couponCount;
    private Integer sortOrder;
    private Integer status;

    public boolean isEnabled() {
        return status != null && status == STATUS_ENABLED;
    }

    public Integer getThresholdPoints() {
        return thresholdPoints;
    }

    public void setThresholdPoints(Integer thresholdPoints) {
        this.thresholdPoints = thresholdPoints;
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
