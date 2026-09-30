package com.pethealth.provider.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

import java.math.BigDecimal;

/**
 * 考核规则（表 {@code assessment_rule}）——**单行表**，{@link #SINGLETON_ID} 是唯一那一行的 id。
 *
 * <p>规则是「当前生效的那一份」，不是可以并存的多个方案（多行会立刻引出「哪一行生效」，
 * 而这一期没有 A/B 的需求）。权重与达标线是**运营会调的业务可调项**（ADR-0010 的分层），
 * 所以入库；算法与判据是代码常量，不在这里。
 *
 * <p>两条关键语义：
 *
 * <ul>
 *   <li>{@code inviteTarget = 0} / {@code couponTarget = 0.00} 不是「很松的目标」，而是
 *       **「该项还没定要求」**：按 ADR-0050 第四节，该维度不参与计分并重算权重，
 *       而不是给它记 0 分（那是「该做而没做」的口径）；
 *   <li>改规则**不影响已算出的账期**：每次算分把当时的权重与达标线快照进月度分表
 *       （{@code assessment_monthly_score} 与明细的 {@code target_value}）。
 * </ul>
 */
@TableName("assessment_rule")
public class AssessmentRule extends BaseEntity {

    /** 单行表的固定 id。种子里就这一行，服务只读写它。 */
    public static final long SINGLETON_ID = 1L;

    /** 达标线为 0 = 未配置（该维度不参与），不是「目标为零」。 */
    public static final BigDecimal UNSET_TARGET = BigDecimal.ZERO;

    private Integer inviteWeight;
    private Integer couponWeight;
    private Integer processWeight;
    private Integer inviteTarget;
    private BigDecimal couponTarget;
    private Integer responseMinutesTarget;

    /** 三项权重之和是否为 100——写接口校验一条、批算前再判一条（规则被手工改坏时不要算出奇怪的数）。 */
    public boolean weightsSumTo100() {
        int sum = intOf(inviteWeight) + intOf(couponWeight) + intOf(processWeight);
        return sum == 100;
    }

    private static int intOf(Integer value) {
        return value == null ? 0 : value;
    }

    public Integer getInviteWeight() {
        return inviteWeight;
    }

    public void setInviteWeight(Integer inviteWeight) {
        this.inviteWeight = inviteWeight;
    }

    public Integer getCouponWeight() {
        return couponWeight;
    }

    public void setCouponWeight(Integer couponWeight) {
        this.couponWeight = couponWeight;
    }

    public Integer getProcessWeight() {
        return processWeight;
    }

    public void setProcessWeight(Integer processWeight) {
        this.processWeight = processWeight;
    }

    public Integer getInviteTarget() {
        return inviteTarget;
    }

    public void setInviteTarget(Integer inviteTarget) {
        this.inviteTarget = inviteTarget;
    }

    public BigDecimal getCouponTarget() {
        return couponTarget;
    }

    public void setCouponTarget(BigDecimal couponTarget) {
        this.couponTarget = couponTarget;
    }

    public Integer getResponseMinutesTarget() {
        return responseMinutesTarget;
    }

    public void setResponseMinutesTarget(Integer responseMinutesTarget) {
        this.responseMinutesTarget = responseMinutesTarget;
    }
}
