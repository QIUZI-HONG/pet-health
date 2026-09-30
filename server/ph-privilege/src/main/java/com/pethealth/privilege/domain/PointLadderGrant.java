package com.pethealth.privilege.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

/**
 * 月度阶梯发放记录，表 {@code point_ladder_grant}。
 *
 * <p>{@code (userId, period)} 唯一：**一个账期每人只发一次**。
 * 批算重跑、多实例并发都命在这条唯一键上——骨架也要幂等，否则第一次补跑就发重了。
 *
 * <p>门槛与累计值一并存档：档位配置后来改了不影响这次记录，
 * 「他为什么拿到这张券」也能事后答上来。
 */
@TableName("point_ladder_grant")
public class PointLadderGrant extends BaseEntity {

    private Long userId;
    /** 账期 yyyy-MM（**上月**）。 */
    private String period;
    private Long tierId;
    private Integer thresholdPoints;
    private Integer cumulativePoints;
    private Long couponId;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getPeriod() {
        return period;
    }

    public void setPeriod(String period) {
        this.period = period;
    }

    public Long getTierId() {
        return tierId;
    }

    public void setTierId(Long tierId) {
        this.tierId = tierId;
    }

    public Integer getThresholdPoints() {
        return thresholdPoints;
    }

    public void setThresholdPoints(Integer thresholdPoints) {
        this.thresholdPoints = thresholdPoints;
    }

    public Integer getCumulativePoints() {
        return cumulativePoints;
    }

    public void setCumulativePoints(Integer cumulativePoints) {
        this.cumulativePoints = cumulativePoints;
    }

    public Long getCouponId() {
        return couponId;
    }

    public void setCouponId(Long couponId) {
        this.couponId = couponId;
    }
}
