package com.pethealth.order.api;

import com.pethealth.order.domain.OrderStatus;

import java.time.LocalDate;

/**
 * 订单侧的**只读统计**：给考核（F022）算分用（ADR-006：别的模块不 join {@code order} 表）。
 *
 * <p>实现类在 {@code com.pethealth.order.service}，由 Spring 注入。**不暴露实体**：
 * 这里只有考核算分要用的四个数，订单的状态机与金额不在对外形状里。
 *
 * <p>三条口径先钉死（考核那一波若不同意，改这里一处即可）：
 *
 * <ol>
 *   <li><b>日期区间按「预约日期」（{@code appointment_date}）</b>而不是下单时间：
 *       考核看的是「这段时间里履约了多少单」，用户提前一周下的单不该算进下单那一天；
 *   <li><b>计数回 0，均值回 {@code null}</b>：0 与 null 在考核里是两种事实——
 *       「这段时间一单没有」与「有单但还没有可算的时长」得分规则不同；
 *   <li><b>「服务者单方取消」只算 {@code cancelled_by = 2}</b>（服务者取消，
 *       ADR-0049 §七的「服务者取消率」）。用户自己取消、运营干预取消都不算在它头上。
 * </ol>
 */
public interface OrderStatsApi {

    /**
     * 状态码取值——**定义在 {@link OrderStatus}**，这里给跨模块的消费者一套不依赖
     * {@code order.domain} 的名字（考核要用「履约中 + 已完成」两个码）。
     */
    int STATUS_IN_SERVICE = OrderStatus.IN_SERVICE;
    int STATUS_COMPLETED = OrderStatus.COMPLETED;

    /**
     * 某服务者在区间内的订单数，可按状态过滤（考核的核销率分母用）。
     *
     * @param status 0 待接单 / 1 已预约 / 2 履约中 / 3 已完成 / 4 已取消；传 {@code null} 表示全部状态
     * @return 单量；没有单回 {@code 0}
     */
    long countByProvider(long providerId, LocalDate from, LocalDate to, Integer status);

    /**
     * 某服务者在区间内**单方取消**的订单数（ADR-0049 §七：服务者取消率）。
     *
     * @return 单量；没有回 {@code 0}
     */
    long countCancelledByProvider(long providerId, LocalDate from, LocalDate to);

    /**
     * 接单响应时长（下单 → 接单）的平均分钟数。
     *
     * @return 平均分钟数；**区间内没有任何「已被接单」的订单时回 {@code null}**
     */
    Long averageResponseMinutes(long providerId, LocalDate from, LocalDate to);

    /**
     * 平均服务时长（**核销 → 报工**，取服务端记录的两个时刻）。
     *
     * @return 平均分钟数；**区间内没有「既有核销又有报工」的订单时回 {@code null}**
     */
    Long averageServiceMinutes(long providerId, LocalDate from, LocalDate to);
}
