package com.pethealth.ai.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

/**
 * 一次 AI 咨询的留痕（表 {@code ai_consult}，迁移 V7）。
 *
 * <p>字段与 AI 服务的响应一一对应，**不做二次加工**：留痕的价值在于「当时到底是什么样」，
 * 加工过的记录事后归因会失真。
 */
@TableName("ai_consult")
public class AiConsult extends BaseEntity {

    private Long userId;
    private Long petId;
    private String questionEnc;
    private Integer imageCount;
    private Integer riskLevel;
    private String possibleCauses;
    private String actionSuggestion;
    private Integer needHospital;
    private String careTips;
    private String redFlagHits;
    private String guardHits;
    private String redFlagCheck;
    private Integer degraded;
    private String degradeReason;
    private String modelName;
    private String modelVersion;
    private String promptVersion;
    private Integer latencyMs;
    /** 本轮实际消耗的 token（红线短路与降级记 0）。日预算告警按它估算花费（ADR-0026）。 */
    private Integer promptTokens;
    private Integer completionTokens;
    /**
     * 本次引用到的知识条目（JSON，只含 vetted）：编号 + 标题 + 来源（切片 #101）。
     *
     * <p>与出给 C 端的那份**同一口径**（只有复核过的条目），另有一列记未复核的那些——
     * 两者分开才能回答「这句话当时是有依据的，还是拿未复核内容生成的」（ADR-0033）。
     */
    private String citations;
    /** 进过上下文的未复核条目编号（JSON）。非空意味着当时的回答必须带「尚未经兽医复核」。 */
    private String unvettedHits;
    /** 命中的分级规则编号（JSON）。归因时要能分清「结论是模型判的还是规则抬的档」（#103）。 */
    private String gradingRuleHits;
    /** 知识检索这一层是否生效：ok / empty / unavailable / disabled / skipped。 */
    private String retrievalCheck;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getPetId() {
        return petId;
    }

    public void setPetId(Long petId) {
        this.petId = petId;
    }

    public String getQuestionEnc() {
        return questionEnc;
    }

    public void setQuestionEnc(String questionEnc) {
        this.questionEnc = questionEnc;
    }

    public Integer getImageCount() {
        return imageCount;
    }

    public void setImageCount(Integer imageCount) {
        this.imageCount = imageCount;
    }

    public Integer getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(Integer riskLevel) {
        this.riskLevel = riskLevel;
    }

    public String getPossibleCauses() {
        return possibleCauses;
    }

    public void setPossibleCauses(String possibleCauses) {
        this.possibleCauses = possibleCauses;
    }

    public String getActionSuggestion() {
        return actionSuggestion;
    }

    public void setActionSuggestion(String actionSuggestion) {
        this.actionSuggestion = actionSuggestion;
    }

    public Integer getNeedHospital() {
        return needHospital;
    }

    public void setNeedHospital(Integer needHospital) {
        this.needHospital = needHospital;
    }

    public String getCareTips() {
        return careTips;
    }

    public void setCareTips(String careTips) {
        this.careTips = careTips;
    }

    public String getRedFlagHits() {
        return redFlagHits;
    }

    public void setRedFlagHits(String redFlagHits) {
        this.redFlagHits = redFlagHits;
    }

    public String getGuardHits() {
        return guardHits;
    }

    public void setGuardHits(String guardHits) {
        this.guardHits = guardHits;
    }

    public String getRedFlagCheck() {
        return redFlagCheck;
    }

    public void setRedFlagCheck(String redFlagCheck) {
        this.redFlagCheck = redFlagCheck;
    }

    public Integer getDegraded() {
        return degraded;
    }

    public void setDegraded(Integer degraded) {
        this.degraded = degraded;
    }

    public String getDegradeReason() {
        return degradeReason;
    }

    public void setDegradeReason(String degradeReason) {
        this.degradeReason = degradeReason;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public void setModelVersion(String modelVersion) {
        this.modelVersion = modelVersion;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Integer latencyMs) {
        this.latencyMs = latencyMs;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    public String getCitations() {
        return citations;
    }

    public void setCitations(String citations) {
        this.citations = citations;
    }

    public String getUnvettedHits() {
        return unvettedHits;
    }

    public void setUnvettedHits(String unvettedHits) {
        this.unvettedHits = unvettedHits;
    }

    public String getGradingRuleHits() {
        return gradingRuleHits;
    }

    public void setGradingRuleHits(String gradingRuleHits) {
        this.gradingRuleHits = gradingRuleHits;
    }

    public String getRetrievalCheck() {
        return retrievalCheck;
    }

    public void setRetrievalCheck(String retrievalCheck) {
        this.retrievalCheck = retrievalCheck;
    }
}
