package com.pethealth.provider.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

import java.math.BigDecimal;

/**
 * 目录外服务提案，表 {@code catalog_item_proposal}——交付文档 F012 与 2.5
 * 「目录外服务须经平台审核」的落点。
 *
 * <p>提案**不改目录**：审核通过后由 ph-provider 调 ph-catalog 的 {@code CatalogItemApi} 建正式项目，
 * 把生成的编码写回 {@code itemCode}。所以这张表与目录之间只有「一次性的创建关系」，
 * 之后这条目录项就独立存在了（平台可以再改它，提案不跟随）。
 *
 * <p>建议区间只是**参考**：最终区间由运营在审核通过时给出（{@code CatalogItemProposalApproveRequest}
 * 的 price_min / price_max 是必填）。让申请方定义平台规则是不成立的。
 */
@TableName("catalog_item_proposal")
public class CatalogItemProposal extends BaseEntity {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_APPROVED = 1;
    public static final int STATUS_REJECTED = 2;

    private Long providerId;
    private String categoryCode;
    private String name;
    private String description;
    private BigDecimal suggestedPriceMin;
    private BigDecimal suggestedPriceMax;
    private String suggestedPriceUnit;
    private Integer status;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String rejectReason;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String itemCode;
    private java.time.LocalDateTime submittedAt;
    private java.time.LocalDateTime reviewedAt;
    private Long reviewerId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String reviewRemark;

    public boolean isPending() {
        return status != null && status == STATUS_PENDING;
    }

    public Long getProviderId() {
        return providerId;
    }

    public void setProviderId(Long providerId) {
        this.providerId = providerId;
    }

    public String getCategoryCode() {
        return categoryCode;
    }

    public void setCategoryCode(String categoryCode) {
        this.categoryCode = categoryCode;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BigDecimal getSuggestedPriceMin() {
        return suggestedPriceMin;
    }

    public void setSuggestedPriceMin(BigDecimal suggestedPriceMin) {
        this.suggestedPriceMin = suggestedPriceMin;
    }

    public BigDecimal getSuggestedPriceMax() {
        return suggestedPriceMax;
    }

    public void setSuggestedPriceMax(BigDecimal suggestedPriceMax) {
        this.suggestedPriceMax = suggestedPriceMax;
    }

    public String getSuggestedPriceUnit() {
        return suggestedPriceUnit;
    }

    public void setSuggestedPriceUnit(String suggestedPriceUnit) {
        this.suggestedPriceUnit = suggestedPriceUnit;
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

    public String getItemCode() {
        return itemCode;
    }

    public void setItemCode(String itemCode) {
        this.itemCode = itemCode;
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
