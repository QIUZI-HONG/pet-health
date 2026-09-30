package com.pethealth.privilege.domain;

/**
 * 按贡献分组的券统计（不是表）——服务者列表与详情里的额度账。
 *
 * <p>口径与 {@link CouponStat} 一致（已发放 / 已核销 / 占用中 / 已过期），
 * 只是分组键换成 {@code contributionId}：一个服务者可能贡献了多种券，每种各算各的账。
 */
public class ContributionCouponStat {

    private Long contributionId;
    private Integer issued;
    private Integer redeemed;
    private Integer reserved;
    private Integer expired;

    public Long getContributionId() {
        return contributionId;
    }

    public void setContributionId(Long contributionId) {
        this.contributionId = contributionId;
    }

    public Integer getIssued() {
        return issued;
    }

    public void setIssued(Integer issued) {
        this.issued = issued;
    }

    public Integer getRedeemed() {
        return redeemed;
    }

    public void setRedeemed(Integer redeemed) {
        this.redeemed = redeemed;
    }

    public Integer getReserved() {
        return reserved;
    }

    public void setReserved(Integer reserved) {
        this.reserved = reserved;
    }

    public Integer getExpired() {
        return expired;
    }

    public void setExpired(Integer expired) {
        this.expired = expired;
    }
}
