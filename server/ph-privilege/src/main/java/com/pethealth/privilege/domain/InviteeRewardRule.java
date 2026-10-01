package com.pethealth.privilege.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

/**
 * 被邀请人奖励（券）：交付文档 BPM-2「双方各得 20 元洗护券」里**被邀请人**那一半（V46，F016）。
 *
 * <p>邀请人一侧的券是 {@code invite_ladder_tier} 的阶梯奖（1 人档就给一张）；被邀请人一侧此前
 * 只有 {@code point_behavior.INVITE_INVITEE} 那条积分路径——「拿到的是积分不是券」正是文档口径
 * 与实现之间的那一处差异。这张**单行**表补的就是它。
 *
 * <p>与积分那条路径的关系：**各自独立、都读配置**。券（本表）与分（{@code point_behavior}）
 * 是同一次注册的两份奖励，关掉任何一条都不影响另一条；发放在同一刻（观察窗结束、关系判有效），
 * 幂等靠券的 {@code (source, source_ref)}（引用 {@code invitee-coupon:{关系 id}}）。
 *
 * <p>与 {@link CheckInRewardRule}（在 ph-record）同一形态：规则入库、运营可改、不改代码路径。
 */
@TableName("invitee_reward_rule")
public class InviteeRewardRule extends BaseEntity {

    /** 单行表的固定主键：规则是「当前生效的那一份」，不是可以并存的多个方案（同 {@code assessment_rule}）。 */
    public static final long SINGLETON_ID = 1L;

    public static final int STATUS_ENABLED = 1;

    private Long couponTemplateId;
    private Integer couponCount;
    private Integer status;

    public boolean isEnabled() {
        return status != null && status == STATUS_ENABLED;
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

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
