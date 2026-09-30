package com.pethealth.record.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 健康报告（V16，按周期一行）。对应交付文档 F008 的「周报/月报」（切片 #115，决策见 ADR-0031）。
 *
 * <p><b>它是派生视图</b>：正文由规则模板从 {@code health_score} 与 {@code archive_record} 拼出来，
 * **不经过模型**——报告里的每个数字都来自数据，模型在这里只能产生幻觉、不能产生信息。
 *
 * <p><b>为什么要落库</b>：报告要能按期回看，而且要看的是**当时**的口径。实时重算做不到这件事——
 * 评分算法或模板以后改了，那段历史会给出与当时不同的结论，而用户会拿两期做对比。
 *
 * <p>{@code payload} 存的是拼好的正文（JSON），{@code grade} / {@code totalScore} 是给列表页
 * 用的汇总列——列表不必解析 JSON 就能渲染一行。
 */
@TableName("health_report")
public class HealthReport extends BaseEntity {

    /** 1 周报（上一个完整自然周）/ 2 月报（上一个完整自然月）。 */
    public static final int TYPE_WEEKLY = 1;
    public static final int TYPE_MONTHLY = 2;

    /** 1 基础版（评分与趋势 + 打卡完成度）/ 2 完整版（另含异常与亮点、建议清单）。 */
    public static final int TIER_BASIC = 1;
    public static final int TIER_FULL = 2;

    private Long petId;
    private Long userId;
    private Integer type;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private String grade;
    private Integer totalScore;
    private Integer tier;
    private String payload;
    private LocalDateTime generatedAt;

    public Long getPetId() {
        return petId;
    }

    public void setPetId(Long petId) {
        this.petId = petId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Integer getType() {
        return type;
    }

    public void setType(Integer type) {
        this.type = type;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public void setPeriodStart(LocalDate periodStart) {
        this.periodStart = periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public void setPeriodEnd(LocalDate periodEnd) {
        this.periodEnd = periodEnd;
    }

    public String getGrade() {
        return grade;
    }

    public void setGrade(String grade) {
        this.grade = grade;
    }

    public Integer getTotalScore() {
        return totalScore;
    }

    public void setTotalScore(Integer totalScore) {
        this.totalScore = totalScore;
    }

    public Integer getTier() {
        return tier;
    }

    public void setTier(Integer tier) {
        this.tier = tier;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public LocalDateTime getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(LocalDateTime generatedAt) {
        this.generatedAt = generatedAt;
    }
}
