package com.pethealth.privilege.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

/**
 * 券贡献的额度流水，表 {@code coupon_contribution_log}——**append-only**。
 *
 * <p>能删就等于没有（与 {@code provider_review_log} 同一取舍）：服务者问「我的额度去哪了」时，
 * 答案要在这张表里查得到，而不是在那个算出来的结果数里。
 *
 * <p>四个动作：承诺 / 调整额度 / 撤回 / 过期释放。
 * **发放与核销不在这里**——它们各自在 {@code coupon} 那一行上留了痕（{@code issued_at} /
 * {@code redeemed_at}），记两遍只会让「哪一份是真的」变成问题。
 */
@TableName("coupon_contribution_log")
public class CouponContributionLog extends BaseEntity {

    public static final int ACTION_COMMIT = 1;
    public static final int ACTION_ADJUST = 2;
    public static final int ACTION_WITHDRAW = 3;
    /** 过期未核销释放额度（系统写入，{@code createdBy=0}）。 */
    public static final int ACTION_RELEASE = 4;

    private Long contributionId;
    private Long providerId;
    private Integer action;
    /** 本次动作后的承诺额度。 */
    private Integer totalCount;
    /** 本次动作后的可发放额度，便于事后复盘「当时还能发多少」。 */
    private Integer availableCount;
    private String remark;

    public Long getContributionId() {
        return contributionId;
    }

    public void setContributionId(Long contributionId) {
        this.contributionId = contributionId;
    }

    public Long getProviderId() {
        return providerId;
    }

    public void setProviderId(Long providerId) {
        this.providerId = providerId;
    }

    public Integer getAction() {
        return action;
    }

    public void setAction(Integer action) {
        this.action = action;
    }

    public Integer getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(Integer totalCount) {
        this.totalCount = totalCount;
    }

    public Integer getAvailableCount() {
        return availableCount;
    }

    public void setAvailableCount(Integer availableCount) {
        this.availableCount = availableCount;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
