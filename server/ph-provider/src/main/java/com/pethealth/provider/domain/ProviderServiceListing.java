package com.pethealth.provider.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

/**
 * 服务者上架的服务项，表 {@code provider_service}——交付文档 7.2 的 {@code merchant_service}（文档用词）。
 *
 * <p>状态机（ADR-0034）：
 *
 * <pre>
 *   （新增/改价）→ 0 待审核 → 运营通过 → 1 已上架 → 服务者下架 → 2 已下架
 *                    ↘ 运营驳回 → 3 已驳回（改价可再回到 0）
 *   2 已下架 →（服务者上架，价格未变，无需重审）→ 1 已上架
 * </pre>
 *
 * <p>两条规则值得单独说：
 *
 * <ul>
 *   <li><b>改价必须重审</b>：价格是对外承诺，改完直接生效等于绕过了平台的区间与合规检查；
 *   <li><b>再上架不需要重审</b>：审核通过之后价格没变，再走一遍审核只是把审核员当橡皮图章。
 * </ul>
 *
 * <p>不存服务项名称快照：名称属于目录，平台改名后服务者页面应当立刻跟着变
 * （跨模块取名字走 ph-catalog 的接口，ADR-0006）。
 */
@TableName("provider_service")
public class ProviderServiceListing extends BaseEntity {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_LISTED = 1;
    public static final int STATUS_DELISTED = 2;
    public static final int STATUS_REJECTED = 3;

    private Long providerId;
    private String serviceCode;
    private java.math.BigDecimal price;
    private Integer status;
    private String rejectReason;
    private java.time.LocalDateTime submittedAt;
    private java.time.LocalDateTime reviewedAt;
    private Long reviewerId;

    public boolean isListed() {
        return status != null && status == STATUS_LISTED;
    }

    public Long getProviderId() {
        return providerId;
    }

    public void setProviderId(Long providerId) {
        this.providerId = providerId;
    }

    public String getServiceCode() {
        return serviceCode;
    }

    public void setServiceCode(String serviceCode) {
        this.serviceCode = serviceCode;
    }

    public java.math.BigDecimal getPrice() {
        return price;
    }

    public void setPrice(java.math.BigDecimal price) {
        this.price = price;
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
}
