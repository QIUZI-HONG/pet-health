package com.pethealth.privilege.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

/**
 * 服务者的券贡献，表 {@code coupon_contribution}——券池里的第二个对象。
 *
 * <p><b>它不是发放，是承诺</b>：「我店愿意接多少张」（ADR-0037 第三节的原文口径）。
 * 发放是平台按任务 / 邀请 / 兑换 / 补贴定向做的事，这个类只管额度。
 *
 * <p>为什么额度不存成一个 {@code availableCount} 列：额度是**算出来的**
 * （{@code 可发放 = total_count − 已核销 − 占用中}）。存成一个列就要在四个地方同步它
 * （发放、核销、过期、调额），而它一旦漂移，「超发」与「发不出去」都不会报错。
 * 计算口径收在 {@code CouponQuota} 一处。
 *
 * <p>{@code (providerId, templateId)} 唯一：一个服务者对一个模板只留**一条额度账**。
 * 「承诺 200 张」改成「300 张」是同一件事的两次编辑；撤回也不新建第二条，
 * 这样「这个服务者在这个券上做过什么」才是连续的一段历史。
 */
@TableName("coupon_contribution")
public class CouponContribution extends BaseEntity {

    public static final int STATUS_ACTIVE = 1;
    /** 已停止发放（服务者撤回，或把额度调到只剩已发出的那部分）。 */
    public static final int STATUS_STOPPED = 2;

    private Long providerId;
    private Long templateId;
    /** 承诺的可核销额度。 */
    private Integer totalCount;
    private Integer status;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String remark;

    public boolean isActive() {
        return status != null && status == STATUS_ACTIVE;
    }

    public Long getProviderId() {
        return providerId;
    }

    public void setProviderId(Long providerId) {
        this.providerId = providerId;
    }

    public Long getTemplateId() {
        return templateId;
    }

    public void setTemplateId(Long templateId) {
        this.templateId = templateId;
    }

    public Integer getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(Integer totalCount) {
        this.totalCount = totalCount;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
