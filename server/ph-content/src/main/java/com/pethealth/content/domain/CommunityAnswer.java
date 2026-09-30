package com.pethealth.content.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

import java.time.LocalDateTime;

/**
 * 回答，表 {@code community_answer}。
 *
 * <p>本表**不存**「我被采纳了没有」：采纳的真值在 {@link CommunityQuestion#getAdoptedAnswerId()}
 * （回答视图里的 {@code adopted} 是从那里派生的展示值）。
 *
 * <p>匿名口径与卡片一致：{@code authorId} 只服务越权判定与「我的回答」，
 * 对外视图只出 {@code mine}。
 *
 * <p>回答的审核状态与提问**互不联动**：提问被下架时，它的回答不必跟着改状态——
 * 公开可见性由读取路径一起判（提问不可见则回答也不可见），而不是靠级联写状态维持两份一致的副本。
 */
@TableName("community_answer")
public class CommunityAnswer extends BaseEntity {

    private Long questionId;
    private Long authorId;
    private String content;
    private Integer status;
    private String machineHits;
    private String rejectReason;
    private LocalDateTime reviewedAt;
    private Long reviewedBy;

    /** 当前时刻能不能被**其他用户**看到：已发布且未删除。 */
    public boolean isPubliclyVisible() {
        return status != null && status == ContentStatus.PUBLISHED;
    }

    public Long getQuestionId() {
        return questionId;
    }

    public void setQuestionId(Long questionId) {
        this.questionId = questionId;
    }

    public Long getAuthorId() {
        return authorId;
    }

    public void setAuthorId(Long authorId) {
        this.authorId = authorId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
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
