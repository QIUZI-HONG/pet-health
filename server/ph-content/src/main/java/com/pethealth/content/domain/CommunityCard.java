package com.pethealth.content.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

import java.time.LocalDateTime;

/**
 * 经验卡片，表 {@code community_card}（迁移 V31 的注释写清了四条设计，这里只重复三条纪律）。
 *
 * <p><b>恒匿名</b>：{@code authorId} 留着是为了三件事——越权判定（能不能看到待审的卡片）、
 * 「我的卡片」、审核追溯。**任何对外视图都不得出现它**（契约里的 {@code CommunityCardView}
 * 没有作者字段，实现里也不许补）。交付文档说的是「默认匿名」，这里收紧成恒匿名，
 * 理由与待澄清见 ADR-0051。
 *
 * <p><b>来源记录是逻辑关联</b>：{@code petId} + {@code sourceType} + {@code sourceRef}。
 * 展示路径永不回读那条记录——档案正文不因社区再暴露一次，这条关联只回答「这张卡片是从哪来的」。
 *
 * <p><b>聚合靠快照标签</b>（{@code species} / {@code breed} / {@code diseaseTag}）：
 * 宠物改名、品种登记被纠正都不改写已经发出去的历史卡片（与订单快照同一口径，见 V29）。
 */
@TableName("community_card")
public class CommunityCard extends BaseEntity {

    /** 来源记录类型：1 打卡。 */
    public static final int SOURCE_CHECK_IN = 1;
    /** 来源记录类型：2 就医记录（档案侧的分项记录）。 */
    public static final int SOURCE_MEDICAL = 2;

    private Long authorId;
    private Long petId;
    private Integer sourceType;
    private String sourceRef;
    private Integer species;
    private String breed;
    private String diseaseTag;
    private String title;
    private String content;
    private Integer likeCount;
    private Integer status;
    private String machineHits;
    private String rejectReason;
    private LocalDateTime reviewedAt;
    private Long reviewedBy;

    /** 来源类型中文名（打卡 / 就医记录）；不认识的值给空而不抛异常（历史数据不该让列表整个失败）。 */
    public static String sourceTypeName(Integer sourceType) {
        if (sourceType == null) {
            return null;
        }
        return switch (sourceType) {
            case SOURCE_CHECK_IN -> "打卡";
            case SOURCE_MEDICAL -> "就医记录";
            default -> null;
        };
    }

    /** 当前时刻能不能被**其他用户**看到：已发布且未删除。 */
    public boolean isPubliclyVisible() {
        return status != null && status == ContentStatus.PUBLISHED;
    }

    public Long getAuthorId() {
        return authorId;
    }

    public void setAuthorId(Long authorId) {
        this.authorId = authorId;
    }

    public Long getPetId() {
        return petId;
    }

    public void setPetId(Long petId) {
        this.petId = petId;
    }

    public Integer getSourceType() {
        return sourceType;
    }

    public void setSourceType(Integer sourceType) {
        this.sourceType = sourceType;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public void setSourceRef(String sourceRef) {
        this.sourceRef = sourceRef;
    }

    public Integer getSpecies() {
        return species;
    }

    public void setSpecies(Integer species) {
        this.species = species;
    }

    public String getBreed() {
        return breed;
    }

    public void setBreed(String breed) {
        this.breed = breed;
    }

    public String getDiseaseTag() {
        return diseaseTag;
    }

    public void setDiseaseTag(String diseaseTag) {
        this.diseaseTag = diseaseTag;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getLikeCount() {
        return likeCount;
    }

    public void setLikeCount(Integer likeCount) {
        this.likeCount = likeCount;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getMachineHits() {
        return machineHits;
    }

    public void setMachineHits(String machineHits) {
        this.machineHits = machineHits;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public void setRejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public Long getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(Long reviewedBy) {
        this.reviewedBy = reviewedBy;
    }
}
