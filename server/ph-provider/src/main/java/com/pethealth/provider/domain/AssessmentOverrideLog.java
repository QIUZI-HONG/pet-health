package com.pethealth.provider.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

import java.math.BigDecimal;

/**
 * 单项分覆盖留痕（表 {@code assessment_override_log}）：**append-only**，每次覆盖追加一条。
 *
 * <p>ADR-0039 第三节的原话：「允许超级管理员覆盖单项分，但必须留痕：谁、何时、理由、覆盖前后值
 * 都留。否则算法是黑箱，服务者无法申诉。」五样东西的落点：
 *
 * <ul>
 *   <li><b>谁</b> → {@code created_by}（框架按 {@code CurrentUser} 填的 operatorId）；
 *   <li><b>何时</b> → {@code created_at}；
 *   <li><b>理由</b> → {@code reason}（必填，且随明细下发给服务者）；
 *   <li><b>前后值</b> → {@code before_score} / {@code after_score}——都是**当时的生效值**，
 *       所以连续覆盖两次会得到两条首尾相接的记录（原值在明细的 {@code calculated_score} 上）。
 * </ul>
 *
 * <p>{@code before_score} 允许为空：覆盖一个「未参与」的项时，覆盖前没有值（NULL ≠ 0）。
 * 不设「撤销」状态列——撤销就是再覆盖一次，审计要的是完整序列而不是最终状态。
 */
@TableName("assessment_override_log")
public class AssessmentOverrideLog extends BaseEntity {

    private Long scoreId;
    private Long providerId;
    private String period;
    private String itemCode;
    private String itemName;
    private BigDecimal beforeScore;
    private BigDecimal afterScore;
    private String reason;

    public Long getScoreId() {
        return scoreId;
    }

    public void setScoreId(Long scoreId) {
        this.scoreId = scoreId;
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

    public String getItemCode() {
        return itemCode;
    }

    public void setItemCode(String itemCode) {
        this.itemCode = itemCode;
    }

    public String getItemName() {
        return itemName;
    }

    public void setItemName(String itemName) {
        this.itemName = itemName;
    }

    public BigDecimal getBeforeScore() {
        return beforeScore;
    }

    public void setBeforeScore(BigDecimal beforeScore) {
        this.beforeScore = beforeScore;
    }

    public BigDecimal getAfterScore() {
        return afterScore;
    }

    public void setAfterScore(BigDecimal afterScore) {
        this.afterScore = afterScore;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
