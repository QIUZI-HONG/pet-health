package com.pethealth.privilege.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

/**
 * 积分行为分值表，表 {@code point_behavior}——ADR-0038 第四节那张表，进库 + 运营可调。
 *
 * <p>行为码是**代码里约定的常量**（发分的一方要知道往哪发），所以这张表
 * **只能改分值 / 频次 / 启停，不能新增行**：新增行为是代码变更，不是配置变更
 * （ADR-0046 的决定）。运营可扩的是任务与档位，不是行为。
 *
 * <p>频次约束三件套：
 *
 * <ul>
 *   <li>{@code dailyCountLimit}：每日次数（签到 / 打卡都是 1 次）；
 *   <li>{@code monthlyCountLimit}：每月次数（评价晒单 5 次）；
 *   <li>{@code onceOnly}：一次性（完善档案 10 分）。
 * </ul>
 */
@TableName("point_behavior")
public class PointBehavior extends BaseEntity {

    /** 每日签到 1 分（每日 1 次，占每日上限）。 */
    public static final String SIGN_IN = "SIGN_IN";
    /** 打卡 3 分（按业务日每日 1 次，占每日上限）。 */
    public static final String CHECK_IN = "CHECK_IN";
    /** 邀请有效注册 20 分（每有效邀请 1 次，**不占**每日上限）。 */
    public static final String INVITE = "INVITE";
    /**
     * 被邀请人奖励（F016 的「双方各得」缺的那一半）。
     *
     * <p>**默认分值 0**（V38；本表里 0 分 = 记行为不发分，与 `AI_ADVICE` / `SHARE` 同一编码）：
     * 运营在积分配置里给它定值之后才会真的发——
     * 「发多少、发什么」是产品决策，代码不替它拍板（ADR-0046 第五节同一条纪律）。
     * 发放时机与邀请人**同期**（观察窗结束、关系判有效那一刻）。
     */
    public static final String INVITE_INVITEE = "INVITE_INVITEE";
    /** 评价晒单 5 分（每单 1 次，每月上限 5 次）。 */
    public static final String REVIEW = "REVIEW";
    /** 完善档案 10 分（一次性，**不占**每日上限）。 */
    public static final String PROFILE_COMPLETE = "PROFILE_COMPLETE";
    /** 查看 AI 建议（任务中心的展示项，**不发分**）。 */
    public static final String AI_ADVICE = "AI_ADVICE";
    /** 分享（任务中心的展示项，**不发分**）。 */
    public static final String SHARE = "SHARE";

    public static final int STATUS_ENABLED = 1;
    public static final int STATUS_DISABLED = 0;

    private String code;
    private String name;
    private Integer points;
    private Integer countsTowardDailyCap;
    private Integer dailyCountLimit;
    private Integer monthlyCountLimit;
    private Integer onceOnly;
    private Integer status;
    private Integer sortOrder;

    public boolean isEnabled() {
        return status != null && status == STATUS_ENABLED;
    }

    /** 是否占每日获取上限——邀请与一次性项不占（否则大额奖励会被日上限吃掉）。 */
    public boolean countsTowardDailyCap() {
        return countsTowardDailyCap != null && countsTowardDailyCap == 1;
    }

    /**
     * 是否一次性。
     *
     * <p>方法名刻意**不用 {@code isOnceOnly}**：它和 {@code getOnceOnly()}（字段 getter）
     * 会被 JavaBeans 判成「同一个属性 onceOnly 的两个重载 getter」，MyBatis 反射时直接报错
     * （实测：{@code updateById} 抛 Illegal overloaded getter）。这也是本仓既有代码里
     * 「布尔语义方法不带 is/get 前缀」的原因（如 {@code countsTowardDailyCap()}）。
     */
    public boolean onceOnly() {
        return onceOnly != null && onceOnly == 1;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getPoints() {
        return points;
    }

    public void setPoints(Integer points) {
        this.points = points;
    }

    public Integer getCountsTowardDailyCap() {
        return countsTowardDailyCap;
    }

    public void setCountsTowardDailyCap(Integer countsTowardDailyCap) {
        this.countsTowardDailyCap = countsTowardDailyCap;
    }

    public Integer getDailyCountLimit() {
        return dailyCountLimit;
    }

    public void setDailyCountLimit(Integer dailyCountLimit) {
        this.dailyCountLimit = dailyCountLimit;
    }

    public Integer getMonthlyCountLimit() {
        return monthlyCountLimit;
    }

    public void setMonthlyCountLimit(Integer monthlyCountLimit) {
        this.monthlyCountLimit = monthlyCountLimit;
    }

    public Integer getOnceOnly() {
        return onceOnly;
    }

    public void setOnceOnly(Integer onceOnly) {
        this.onceOnly = onceOnly;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }
}
