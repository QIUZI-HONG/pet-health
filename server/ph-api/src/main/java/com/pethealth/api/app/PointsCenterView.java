package com.pethealth.api.app;

import com.pethealth.api.privilege.PointsDtos;

import java.util.List;

/**
 * 积分中心，对应 contract/app.yaml 的 {@code PointsCenterView}（ADR-0038 第四节 / ADR-0046）。
 *
 * <p>一次给齐这一页要用的四块：账户（余额 / 今日已得 / 每日上限）、任务进度、行为分值表、兑换档位。
 * 前三块都是**读出来的**：任务进度按行为流水聚合，任务表里不存进度——否则进度有两个来源（ADR-0046 第七节）。
 *
 * <p>积分只有分值与余额：**不能兑换现金、不能提现**（CONTEXT.md 的积分条目）。
 * {@code behaviors} 与 {@code exchangeOptions} 直接复用管理端的形状，不另造一套
 * （同一个事实两处形状是漂移的起点）。
 */
public record PointsCenterView(
        Integer balance,
        Integer todayEarned,
        Integer dailyEarnLimit,
        List<PointTaskProgressView> tasks,
        List<PointsDtos.PointBehaviorView> behaviors,
        List<PointsDtos.PointExchangeOptionView> exchangeOptions) {
}
