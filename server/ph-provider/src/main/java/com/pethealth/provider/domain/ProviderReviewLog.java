package com.pethealth.provider.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

/**
 * 审核流水，表 {@code provider_review_log}——交付文档要求的「留审核流水：谁、何时、结论、理由」。
 *
 * <p><b>append-only</b>：只插入、不更新、不删除（{@code isDeleted} 恒为 0）。能删就等于没有——
 * 与 ADR-0028 对审计的取舍一致。所以这里的行数会随驳回重提增长，这是有意的：
 * 申请单上只留最后一次结论，而「被退过几次、每次为什么」正是复盘要看的。
 *
 * <p>三类审核共用一张表（{@code targetType} 区分）：入驻申请 / 服务上架 / 目录外提案。
 * 它们的结构完全同构（谁、何时、结论、理由），各建一张表只会让「审核流水」这件事有三个实现。
 */
@TableName("provider_review_log")
public class ProviderReviewLog extends BaseEntity {

    public static final int TARGET_ONBOARDING = 1;
    public static final int TARGET_LISTING = 2;
    public static final int TARGET_PROPOSAL = 3;
    /** 服务者自身的经营状态变更（冻结 / 解冻）。 */
    public static final int TARGET_PROVIDER = 4;

    public static final int ACTION_SUBMIT = 1;
    public static final int ACTION_RESUBMIT = 2;
    public static final int ACTION_APPROVE = 3;
    public static final int ACTION_REJECT = 4;
    public static final int ACTION_LIST = 5;
    public static final int ACTION_DELIST = 6;
    /** 冻结接单（清退的落点，由超级管理员执行）。 */
    public static final int ACTION_FREEZE = 7;
    /** 解冻。 */
    public static final int ACTION_UNFREEZE = 8;
    /**
     * 改联盟分类归属（V43 起由运营执行）。
     *
     * <p>它不是「审核」动作，但仍然写在这张 append-only 表里：归属会进考核与流量分配的输入，
     * 与冻结一样属于「事后要能查是谁改的」那一类。靠 {@code targetType = TARGET_PROVIDER}
     * 与实际审核流水区分开，读法不变。
     */
    public static final int ACTION_ASSIGN_ALLIANCE = 9;
    /**
     * 改区域编码（V45 起由运营执行）。
     *
     * <p>与联盟分类同一条理由：它决定「C 端按区域筛选时谁能被看到」，属于要看得到是谁改的那类动作。
     */
    public static final int ACTION_ASSIGN_REGION = 10;

    private Integer targetType;
    private Long targetId;
    private Long providerId;
    private Integer action;
    private Long actorId;
    private String actorDomain;
    private String remark;

    public Integer getTargetType() {
        return targetType;
    }

    public void setTargetType(Integer targetType) {
        this.targetType = targetType;
    }

    public Long getTargetId() {
        return targetId;
    }

    public void setTargetId(Long targetId) {
        this.targetId = targetId;
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

    public Long getActorId() {
        return actorId;
    }

    public void setActorId(Long actorId) {
        this.actorId = actorId;
    }

    public String getActorDomain() {
        return actorDomain;
    }

    public void setActorDomain(String actorDomain) {
        this.actorDomain = actorDomain;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
