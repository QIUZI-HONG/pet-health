package com.pethealth.provider.service;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 一次考核取到的**事实**（还没做任何判断）。与判断分开的理由：
 * 「取数」跨模块、会失败、会在没有实现时降级；「算分」是纯函数、可以被逐条对照。
 * 混在一起写会让「这个 0 分是因为门店没做，还是因为数据源没接线」永远分不清。
 *
 * <p>三条口径沿用 {@code OrderStatsApi} 与 {@code ProviderGrowthFactsApi} 的约定：
 *
 * <ul>
 *   <li><b>计数回 0、没有该维度回 null</b>：{@code orderTotal = 0} 是「这段时间一单没有」，
 *       而 {@code averageResponseMinutes = null} 是「有单但没有可算的响应」——两者在考核里
 *       是两种事实（ADR-0049 的考核口径），不能混成同一个 0；
 *   <li><b>{@code growthWired = false} 表示增长侧的事实源未接线</b>（ph-privilege 的
 *       {@code ProviderGrowthFactsApi} 还没有实现）：这时拉新与券两项**不参与**并把原因写进明细，
 *       而不是按 0 分处理——平台没给的数不该记在服务者头上；
 *   <li><b>{@code effectiveInvites = null} 与 {@code 0} 是两件事</b>：前者是「平台侧还没有给这个
 *       服务者拉新入口」（不参与），后者是「有入口但一条有效邀请都没有」（按第一口径记 0 分）。
 *
 * @param from 账期开始（上月 1 日）
 * @param to   账期结束（上月末）——订单统计按**预约日期**落在这个闭区间（ADR-0049 的口径）
 */
record AssessmentFacts(
        LocalDate from,
        LocalDate to,
        long orderTotal,
        long orderInProgress,
        long orderDone,
        long providerCancelled,
        Long averageResponseMinutes,
        boolean growthWired,
        Integer effectiveInvites,
        int contributableTemplates,
        int couponRedeemedCount,
        BigDecimal couponCompletionRate) {

    /** 已核销的订单数 = 履约中 + 已完成（核销把「已预约」推进到「履约中」，ADR-0038 第一节）。 */
    long redeemedOrders() {
        return orderInProgress + orderDone;
    }
}
