package com.pethealth.record.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 健康档案记录（打卡的载体）。对应交付文档 7.2 的 {@code archive_record}，
 * 两处偏离见 ADR-0018：{@link #recordDate}（补录需要独立的业务日期）、{@link #abnormal}（评分要聚合它）。
 */
@TableName("archive_record")
public class ArchiveRecord extends BaseEntity {

    /** 分项编号，与交付文档 7.2 的 category 对齐。打卡只用 1–6。 */
    public static final int CATEGORY_WEIGHT = 1;
    public static final int CATEGORY_DIET = 2;
    public static final int CATEGORY_EXCRETION = 3;
    public static final int CATEGORY_BEHAVIOR = 4;
    public static final int CATEGORY_MOOD = 5;
    public static final int CATEGORY_HYGIENE = 6;
    public static final int CATEGORY_EPIDEMIC = 7;
    /** 就医记录（ADR-0030：它不是 8 个分项之一，是时间轴的一类事件）。 */
    public static final int CATEGORY_MEDICAL = 8;
    /** 其他核心指标：体温、心率、呼吸、饮水这类「一个名字 + 一个数值 + 一个单位」。 */
    public static final int CATEGORY_METRIC_OTHER = 10;
    /** 证件与合规里的证件（免疫证、犬证等；防疫记录是 category=7，两者同属一个分项）。 */
    public static final int CATEGORY_DOCUMENT = 11;
    /** 老年专项：复查、用药与慢病观察（专项照护开启后才有意义，见 ADR-0032）。 */
    public static final int CATEGORY_ELDERLY = 12;

    /** 防疫分项的两种子类型（存在 content.kind 里）。 */
    public static final String EPIDEMIC_VACCINE = "vaccine";
    public static final String EPIDEMIC_DEWORM = "deworm";

    public static final int SOURCE_USER = 1;
    public static final int SOURCE_AI = 2;
    public static final int SOURCE_PROVIDER = 3;

    private Long petId;
    private Long userId;
    /** 业务日期：当场录入=当天，补录=补的那一天。 */
    private LocalDate recordDate;

    /**
     * 下次应接种日（防疫分项专用；空 = 不提醒）。
     * 提醒规则要按它查「7 天内到期」，所以必须是独立的列而不是塞在 content 的 JSON 里。
     */
    private LocalDate dueOn;

    /** 数值型分项的取值（体重等），趋势提醒要算变化幅度。 */
    private BigDecimal numericValue;
    private Integer category;

    /**
     * 扁平载荷（打卡与防疫在用，VARCHAR(1024) 的键值 JSON）。
     *
     * <p>分项记录**不写这里**：它们写 {@link #structuredPayload}（JSON 列）。
     * 两列并存是 ADR-0030 记下的取舍——打卡与防疫的四个既有写路径都依赖这一列，
     * 改它等于同时改四处与它们的测试，收益只是少一列。读接口把两者归一成同一个 payload 视图。
     */
    private String content;

    /**
     * 分项记录的结构化载荷（ADR-0023 点名的列，V15 补上）。
     *
     * <p>形状是 {@code {"title":..,"value":..,"unit":..,"note":..}}；**结构由代码守**
     * （{@code ArchiveSectionService} 按分项校验），数据库只保证它是合法 JSON。
     */
    private String structuredPayload;
    private Integer score;
    private String images;
    private Integer abnormal;
    private Integer backfilled;
    private Integer source;

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

    public LocalDate getRecordDate() {
        return recordDate;
    }

    public void setRecordDate(LocalDate recordDate) {
        this.recordDate = recordDate;
    }

    public LocalDate getDueOn() {
        return dueOn;
    }

    public void setDueOn(LocalDate dueOn) {
        this.dueOn = dueOn;
    }

    public BigDecimal getNumericValue() {
        return numericValue;
    }

    public void setNumericValue(BigDecimal numericValue) {
        this.numericValue = numericValue;
    }

    public Integer getCategory() {
        return category;
    }

    public void setCategory(Integer category) {
        this.category = category;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getStructuredPayload() {
        return structuredPayload;
    }

    public void setStructuredPayload(String structuredPayload) {
        this.structuredPayload = structuredPayload;
    }

    public Integer getScore() {
        return score;
    }

    public void setScore(Integer score) {
        this.score = score;
    }

    public String getImages() {
        return images;
    }

    public void setImages(String images) {
        this.images = images;
    }

    public Integer getAbnormal() {
        return abnormal;
    }

    public void setAbnormal(Integer abnormal) {
        this.abnormal = abnormal;
    }

    public Integer getBackfilled() {
        return backfilled;
    }

    public void setBackfilled(Integer backfilled) {
        this.backfilled = backfilled;
    }

    public Integer getSource() {
        return source;
    }

    public void setSource(Integer source) {
        this.source = source;
    }

    public boolean isAbnormalFlag() {
        return abnormal != null && abnormal == 1;
    }

    public boolean isBackfilledFlag() {
        return backfilled != null && backfilled == 1;
    }
}
