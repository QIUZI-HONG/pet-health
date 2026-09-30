package com.pethealth.privilege.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

import java.time.LocalDate;

/**
 * 积分流水，表 {@code point_record}——每一次变动都留痕，并带着**变动后余额**。
 *
 * <p>三条口径：
 *
 * <ul>
 *   <li>{@code changeAmount} 可以是 0：「查看 AI 建议」「分享」这类任务只统计行为、不发分，
 *       但也走同一张流水——另建一张「行为表」会让任务进度有第二个真相；
 *   <li>{@code businessDate} 是东八区业务日：每日上限与每日频次都按它算，
 *       按 {@code createdAt} 算会在跨零点时出错（用户在 23:59 与 00:01 各点一次不该算两天）；
 *   <li>{@code (userId, behaviorCode, sourceRef)} 唯一：同一行为的同一引用只记一次——
 *       「同一行为不得重复发奖励」在积分侧的落点，靠数据库而不是靠调用方小心。
 * </ul>
 */
@TableName("point_record")
public class PointRecord extends BaseEntity {

    private Long userId;
    private String behaviorCode;
    /** 变动值（列名 {@code change_amount}：`change` 是 MySQL 保留字）。 */
    private Integer changeAmount;
    private Integer balanceAfter;
    private Integer countsTowardDailyCap;
    private LocalDate businessDate;
    private String sourceRef;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String remark;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getBehaviorCode() {
        return behaviorCode;
    }

    public void setBehaviorCode(String behaviorCode) {
        this.behaviorCode = behaviorCode;
    }

    public Integer getChangeAmount() {
        return changeAmount;
    }

    public void setChangeAmount(Integer changeAmount) {
        this.changeAmount = changeAmount;
    }

    public Integer getBalanceAfter() {
        return balanceAfter;
    }

    public void setBalanceAfter(Integer balanceAfter) {
        this.balanceAfter = balanceAfter;
    }

    public Integer getCountsTowardDailyCap() {
        return countsTowardDailyCap;
    }

    public void setCountsTowardDailyCap(Integer countsTowardDailyCap) {
        this.countsTowardDailyCap = countsTowardDailyCap;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public void setBusinessDate(LocalDate businessDate) {
        this.businessDate = businessDate;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public void setSourceRef(String sourceRef) {
        this.sourceRef = sourceRef;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
