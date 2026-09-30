package com.pethealth.provider.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 入驻申请单，表 {@code provider_onboarding_application}。
 *
 * <p>一份申请对应一条 {@code provider}（申请即建，状态待审核）：**驳回重提不新建服务者记录**
 * （2026-09-29 项目所有者的口径）——同一份申请改完重提，{@code submitCount} 加一，
 * 于是「这家服务者被退回过几次」在库里查得到。
 *
 * <p>{@code rejectReason} 是给服务者看的那句话（合同要求「知道卡在哪」）；
 * 历次结论与理由在 {@code provider_review_log}（append-only），本表只留最后一次。
 */
@TableName("provider_onboarding_application")
public class OnboardingApplication extends BaseEntity {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_APPROVED = 1;
    public static final int STATUS_REJECTED = 2;

    private Long providerId;
    private Long applicantUserId;
    private String applicantName;
    private String contactPhoneEnc;
    private String contactPhoneHash;
    private Integer status;
    /**
     * 驳回原因。重提时清空，所以要允许 null 写进 UPDATE（否则「清空」只在内存里生效，
     * 通过之后服务者还能看见上一次的驳回原因）。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String rejectReason;
    private Integer submitCount;
    private java.time.LocalDateTime submittedAt;
    private java.time.LocalDateTime reviewedAt;
    private Long reviewerId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String reviewRemark;

    public boolean isPending() {
        return status != null && status == STATUS_PENDING;
    }

    public boolean isRejected() {
        return status != null && status == STATUS_REJECTED;
    }

    public Long getProviderId() {
        return providerId;
    }

    public void setProviderId(Long providerId) {
        this.providerId = providerId;
    }

    public Long getApplicantUserId() {
        return applicantUserId;
    }

    public void setApplicantUserId(Long applicantUserId) {
        this.applicantUserId = applicantUserId;
    }

    public String getApplicantName() {
        return applicantName;
    }

    public void setApplicantName(String applicantName) {
        this.applicantName = applicantName;
    }

    public String getContactPhoneEnc() {
        return contactPhoneEnc;
    }

    public void setContactPhoneEnc(String contactPhoneEnc) {
        this.contactPhoneEnc = contactPhoneEnc;
    }

    public String getContactPhoneHash() {
        return contactPhoneHash;
    }

    public void setContactPhoneHash(String contactPhoneHash) {
        this.contactPhoneHash = contactPhoneHash;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public void setRejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
    }

    public Integer getSubmitCount() {
        return submitCount;
    }

    public void setSubmitCount(Integer submitCount) {
        this.submitCount = submitCount;
    }

    public java.time.LocalDateTime getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(java.time.LocalDateTime submittedAt) {
        this.submittedAt = submittedAt;
    }

    public java.time.LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(java.time.LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public Long getReviewerId() {
        return reviewerId;
    }

    public void setReviewerId(Long reviewerId) {
        this.reviewerId = reviewerId;
    }

    public String getReviewRemark() {
        return reviewRemark;
    }

    public void setReviewRemark(String reviewRemark) {
        this.reviewRemark = reviewRemark;
    }
}
