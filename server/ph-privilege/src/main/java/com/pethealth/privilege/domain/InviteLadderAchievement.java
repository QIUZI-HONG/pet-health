package com.pethealth.privilege.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

import java.time.LocalDateTime;

/**
 * 邀请阶梯达成记录，表 {@code invite_ladder_achievement}。
 *
 * <p>{@code (userId, threshold)} 唯一：**阶梯奖只在达到门槛时发一次**——
 * 这正是 ADR-0038 第四节点名要钉死的「同一行为不得重复发奖励」的一种情况
 * （达成之后每次新邀请都不该再发一遍同一档的奖）。
 *
 * <p>发放结果（券 / 权益授予）一并存档：配置后来改了也不影响这次记录，
 * 「这个人的券是哪来的」也能一句话答上来。
 */
@TableName("invite_ladder_achievement")
public class InviteLadderAchievement extends BaseEntity {

    private Long userId;
    private Integer threshold;
    private Integer effectiveCount;
    private Integer rewardType;
    private Long couponId;
    private Long rightsGrantId;
    private LocalDateTime achievedAt;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Integer getThreshold() {
        return threshold;
    }

    public void setThreshold(Integer threshold) {
        this.threshold = threshold;
    }

    public Integer getEffectiveCount() {
        return effectiveCount;
    }

    public void setEffectiveCount(Integer effectiveCount) {
        this.effectiveCount = effectiveCount;
    }

    public Integer getRewardType() {
        return rewardType;
    }

    public void setRewardType(Integer rewardType) {
        this.rewardType = rewardType;
    }

    public Long getCouponId() {
        return couponId;
    }

    public void setCouponId(Long couponId) {
        this.couponId = couponId;
    }

    public Long getRightsGrantId() {
        return rightsGrantId;
    }

    public void setRightsGrantId(Long rightsGrantId) {
        this.rightsGrantId = rightsGrantId;
    }

    public LocalDateTime getAchievedAt() {
        return achievedAt;
    }

    public void setAchievedAt(LocalDateTime achievedAt) {
        this.achievedAt = achievedAt;
    }
}
