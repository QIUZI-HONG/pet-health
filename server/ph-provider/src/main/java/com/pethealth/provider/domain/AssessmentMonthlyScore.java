package com.pethealth.provider.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 月度考核分（表 {@code assessment_monthly_score}）：一个服务者一个账期一条。
 *
 * <p>{@code (providerId, period)} 唯一——**它就是月度批算的幂等键**：重跑、多实例并发、
 * 运营手动补跑，都只会有一条（撞唯一键的那次忽略）。这里刻意不做「先查后插 + 加锁」：
 * 唯一键是数据库能提供的最强保证，而应用层的先查后插在并发下会双双通过（ADR-0044 的教训）。
 *
 * <p>权重与达标线是**快照**：规则可改（V34），而历史账期必须解释得清「当时为什么是这个分」。
 * 三个主项的 {@code *Score} 为 {@code null} 与 {@code *Participated = 0} 是同一件事的两种表达，
 * 冗余保留是为了让聚合查询不必处理 NULL——**未参与写 NULL 而不是 0**：写 0 会被
 * SUM/AVG 当成「这项得了 0 分」，而这两件事在考核里必须分开（ADR-0050 第四节）。
 */
@TableName("assessment_monthly_score")
public class AssessmentMonthlyScore extends BaseEntity {

    public static final int NOT_PARTICIPATED = 0;
    public static final int PARTICIPATED = 1;

    private Long providerId;
    private String period;
    private Integer inviteWeight;
    private BigDecimal inviteScore;
    private Integer inviteParticipated;
    private Integer couponWeight;
    private BigDecimal couponScore;
    private Integer couponParticipated;
    private Integer processWeight;
    private BigDecimal processScore;
    private Integer processParticipated;
    private Integer participatedWeight;
    private BigDecimal totalScore;
    private Integer level;
    private String levelName;
    private Integer recommendPriority;
    private Integer overridden;
    private LocalDateTime calculatedAt;

    /** 本期是否发生过覆盖（覆盖留痕在 {@code assessment_override_log} 里）。 */
    public boolean hasOverride() {
        return overridden != null && overridden == 1;
    }

    public Long getProviderId() {
        return providerId;
    }

    public void setProviderId(Long providerId) {
        this.providerId = providerId;
    }

    public String getPeriod() {
        return period;
    }

    public void setPeriod(String period) {
        this.period = period;
    }

    public Integer getInviteWeight() {
        return inviteWeight;
    }

    public void setInviteWeight(Integer inviteWeight) {
        this.inviteWeight = inviteWeight;
    }

    public BigDecimal getInviteScore() {
        return inviteScore;
    }

    public void setInviteScore(BigDecimal inviteScore) {
        this.inviteScore = inviteScore;
    }

    public Integer getInviteParticipated() {
        return inviteParticipated;
    }

    public void setInviteParticipated(Integer inviteParticipated) {
        this.inviteParticipated = inviteParticipated;
    }

    public Integer getCouponWeight() {
        return couponWeight;
    }

    public void setCouponWeight(Integer couponWeight) {
        this.couponWeight = couponWeight;
    }

    public BigDecimal getCouponScore() {
        return couponScore;
    }

    public void setCouponScore(BigDecimal couponScore) {
        this.couponScore = couponScore;
    }

    public Integer getCouponParticipated() {
        return couponParticipated;
    }

    public void setCouponParticipated(Integer couponParticipated) {
        this.couponParticipated = couponParticipated;
    }

    public Integer getProcessWeight() {
        return processWeight;
    }

    public void setProcessWeight(Integer processWeight) {
        this.processWeight = processWeight;
    }

    public BigDecimal getProcessScore() {
        return processScore;
    }

    public void setProcessScore(BigDecimal processScore) {
        this.processScore = processScore;
    }

    public Integer getProcessParticipated() {
        return processParticipated;
    }

    public void setProcessParticipated(Integer processParticipated) {
        this.processParticipated = processParticipated;
    }

    public Integer getParticipatedWeight() {
        return participatedWeight;
    }

    public void setParticipatedWeight(Integer participatedWeight) {
        this.participatedWeight = participatedWeight;
    }

    public BigDecimal getTotalScore() {
        return totalScore;
    }

    public void setTotalScore(BigDecimal totalScore) {
        this.totalScore = totalScore;
    }

    public Integer getLevel() {
        return level;
    }

    public void setLevel(Integer level) {
        this.level = level;
    }

    public String getLevelName() {
        return levelName;
    }

    public void setLevelName(String levelName) {
        this.levelName = levelName;
    }

    public Integer getRecommendPriority() {
        return recommendPriority;
    }

    public void setRecommendPriority(Integer recommendPriority) {
        this.recommendPriority = recommendPriority;
    }

    public Integer getOverridden() {
        return overridden;
    }

    public void setOverridden(Integer overridden) {
        this.overridden = overridden;
    }

    public LocalDateTime getCalculatedAt() {
        return calculatedAt;
    }

    public void setCalculatedAt(LocalDateTime calculatedAt) {
        this.calculatedAt = calculatedAt;
    }
}
