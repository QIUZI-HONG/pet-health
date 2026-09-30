package com.pethealth.content.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

import java.time.LocalDateTime;

/**
 * 提问，表 {@code community_question}（迁移 V32 的注释写清了采纳的三条口径）。
 *
 * <p>{@code adoptedAnswerId} 是「这条问题被解决了没有」的**唯一真值**：
 * 回答行上不存 {@code isAdopted} 副本——同一个事实存两处，迟早会出现
 * 「问题说采纳了 A、A 说自己没被采纳」。
 *
 * <p>采纳的并发正确性靠**条件更新**（{@code WHERE adopted_answer_id IS NULL}），
 * 不靠先查后判：后者在并发下会双通过（ADR-0044 记的同类问题）。
 * 采纳**不回退**——没有「取消采纳」这个动作。
 *
 * <p>归属：只有提问者能采纳，判据是 {@code authorId == 当前用户}，
 * 不匹配按**不存在**处理（40400，不泄露 id 是否存在，docs/conventions.md 的越权口径）。
 */
@TableName("community_question")
public class CommunityQuestion extends BaseEntity {

    private Long authorId;
    private String title;
    private String content;
    private String diseaseTag;
    private Integer answerCount;
    private Long adoptedAnswerId;
    private LocalDateTime adoptedAt;
    private Integer status;
    private String machineHits;
    private String rejectReason;
    private LocalDateTime reviewedAt;
    private Long reviewedBy;

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

    public String getDiseaseTag() {
        return diseaseTag;
    }

    public void setDiseaseTag(String diseaseTag) {
        this.diseaseTag = diseaseTag;
    }

    public Integer getAnswerCount() {
        return answerCount;
    }

    public void setAnswerCount(Integer answerCount) {
        this.answerCount = answerCount;
    }

    public Long getAdoptedAnswerId() {
        return adoptedAnswerId;
    }

    public void setAdoptedAnswerId(Long adoptedAnswerId) {
        this.adoptedAnswerId = adoptedAnswerId;
    }

    public LocalDateTime getAdoptedAt() {
        return adoptedAt;
    }

    public void setAdoptedAt(LocalDateTime adoptedAt) {
        this.adoptedAt = adoptedAt;
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
