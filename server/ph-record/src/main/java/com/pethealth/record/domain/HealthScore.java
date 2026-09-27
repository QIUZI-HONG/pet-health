package com.pethealth.record.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

import java.time.LocalDate;

/**
 * 每日健康评分（一只宠物一天一行）。对应交付文档 7.2 的 {@code health_score} + ADR-0011 的审计列。
 *
 * <p>写入时实时重算当日行——F005 要求打卡「有反馈」，等定时任务批算就看不到即时变化（ADR-0018）。
 * 历史行保留，供趋势与周报（#115）使用。
 *
 * <p>维度字段为 {@code null} 表示**该维未计入总分**：防疫无疫苗/驱虫记录、老年专项未开启。
 * 这与「得了 0 分」是两件事，靠 {@code includedDimensions} 反推当时计入了几个维度。
 */
@TableName("health_score")
public class HealthScore extends BaseEntity {

    private Long petId;
    private Integer totalScore;
    private Integer physiology;
    private Integer behavior;
    private Integer hygiene;
    private Integer epidemic;
    private Integer elderly;
    private Integer includedDimensions;
    private LocalDate calcDate;

    public Long getPetId() {
        return petId;
    }

    public void setPetId(Long petId) {
        this.petId = petId;
    }

    public Integer getTotalScore() {
        return totalScore;
    }

    public void setTotalScore(Integer totalScore) {
        this.totalScore = totalScore;
    }

    public Integer getPhysiology() {
        return physiology;
    }

    public void setPhysiology(Integer physiology) {
        this.physiology = physiology;
    }

    public Integer getBehavior() {
        return behavior;
    }

    public void setBehavior(Integer behavior) {
        this.behavior = behavior;
    }

    public Integer getHygiene() {
        return hygiene;
    }

    public void setHygiene(Integer hygiene) {
        this.hygiene = hygiene;
    }

    public Integer getEpidemic() {
        return epidemic;
    }

    public void setEpidemic(Integer epidemic) {
        this.epidemic = epidemic;
    }

    public Integer getElderly() {
        return elderly;
    }

    public void setElderly(Integer elderly) {
        this.elderly = elderly;
    }

    public Integer getIncludedDimensions() {
        return includedDimensions;
    }

    public void setIncludedDimensions(Integer includedDimensions) {
        this.includedDimensions = includedDimensions;
    }

    public LocalDate getCalcDate() {
        return calcDate;
    }

    public void setCalcDate(LocalDate calcDate) {
        this.calcDate = calcDate;
    }
}
