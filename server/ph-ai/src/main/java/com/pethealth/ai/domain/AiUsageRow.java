package com.pethealth.ai.domain;

/**
 * 按「模型 × 版本」分组的用量统计（**不是表**，是聚合查询的结果）。
 *
 * <p>只装事实：调用数、输入/输出 token、红线短路数、降级数。**单价与补贴比例不在这里**——
 * 它们是运营假设，只作页面参数乘一下展示，不进库（ADR-0050 第五节的立场：假设值入库会让
 * 「测算结果」看起来比它实际的可信度高）。
 */
public class AiUsageRow {

    private String modelName;
    private String modelVersion;
    private Long calls;
    private Long promptTokens;
    private Long completionTokens;
    private Long redFlagCalls;
    private Long degradedCalls;

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

    public Long getCalls() {
        return calls;
    }

    public void setCalls(Long calls) {
        this.calls = calls;
    }

    public Long getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Long promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Long getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Long completionTokens) {
        this.completionTokens = completionTokens;
    }

    public Long getRedFlagCalls() {
        return redFlagCalls;
    }

    public void setRedFlagCalls(Long redFlagCalls) {
        this.redFlagCalls = redFlagCalls;
    }

    public Long getDegradedCalls() {
        return degradedCalls;
    }

    public void setDegradedCalls(Long degradedCalls) {
        this.degradedCalls = degradedCalls;
    }
}
