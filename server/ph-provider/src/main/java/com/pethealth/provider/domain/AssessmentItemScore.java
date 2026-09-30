package com.pethealth.provider.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

import java.math.BigDecimal;

/**
 * 考核分数明细（表 {@code assessment_item_score}）：三项主项 + 过程分的五个子项。
 *
 * <p>{@code (scoreId, itemCode)} 唯一。**未参与的项也落一行**（{@code score} 为 NULL）：
 * 只落参与项的话，「这个过程分为什么是 100」在数据上就看不出来——过程子项被剔除过。
 *
 * <p>{@code calculatedScore} 是**算法算出来的原值**，{@code score} 是当前生效值：
 * 覆盖只改后者（并往 {@code assessment_override_log} 追加一条），
 * 前者是「还原」与申诉的依据（服务者可以要求把覆盖撤回去）。
 */
@TableName("assessment_item_score")
public class AssessmentItemScore extends BaseEntity {

    private Long scoreId;
    private Long providerId;
    private String period;
    private String itemCode;
    private String itemName;
    private String parentCode;
    private Integer weight;
    private Integer participated;
    private BigDecimal score;
    private BigDecimal calculatedScore;
    private String rawValue;
    private String targetValue;
    private String dataSource;
    private String note;
    private Integer overridden;

    public boolean hasParticipated() {
        return participated != null && participated == AssessmentMonthlyScore.PARTICIPATED;
    }

    public boolean hasOverride() {
        return overridden != null && overridden == 1;
    }

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

    public String getParentCode() {
        return parentCode;
    }

    public void setParentCode(String parentCode) {
        this.parentCode = parentCode;
    }

    public Integer getWeight() {
        return weight;
    }

    public void setWeight(Integer weight) {
        this.weight = weight;
    }

    public Integer getParticipated() {
        return participated;
    }

    public void setParticipated(Integer participated) {
        this.participated = participated;
    }

    public BigDecimal getScore() {
        return score;
    }

    public void setScore(BigDecimal score) {
        this.score = score;
    }

    public BigDecimal getCalculatedScore() {
        return calculatedScore;
    }

    public void setCalculatedScore(BigDecimal calculatedScore) {
        this.calculatedScore = calculatedScore;
    }

    public String getRawValue() {
        return rawValue;
    }

    public void setRawValue(String rawValue) {
        this.rawValue = rawValue;
    }

    public String getTargetValue() {
        return targetValue;
    }

    public void setTargetValue(String targetValue) {
        this.targetValue = targetValue;
    }

    public String getDataSource() {
        return dataSource;
    }

    public void setDataSource(String dataSource) {
        this.dataSource = dataSource;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Integer getOverridden() {
        return overridden;
    }

    public void setOverridden(Integer overridden) {
        this.overridden = overridden;
    }
}
