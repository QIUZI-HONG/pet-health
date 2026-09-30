package com.pethealth.order.service;

import com.pethealth.order.api.OrderStatsApi;
import com.pethealth.order.mapper.ServiceOrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 订单侧只读统计的实现（{@link OrderStatsApi}）。
 *
 * <p>薄薄一层：口径全在 SQL 里（`ServiceOrderMapper` 的四条聚合），这里只负责把
 * 「计数回 0、均值回 null」这条语义原样透出去——**不要**在这里把 null 补成 0，
 * 那会把「这段时间一单没有」与「有单但还没有可算的时长」混成同一个事实（ADR-0049 的考核口径）。
 *
 * <p>只读：整个类是 {@code readOnly}，不存在任何写路径，考核那边怎么调都不会改动订单。
 */
@Service
public class OrderStatsService implements OrderStatsApi {

    private final ServiceOrderMapper orderMapper;

    public OrderStatsService(ServiceOrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public long countByProvider(long providerId, LocalDate from, LocalDate to, Integer status) {
        return orderMapper.countByProvider(providerId, from, to, status);
    }

    @Override
    @Transactional(readOnly = true)
    public long countCancelledByProvider(long providerId, LocalDate from, LocalDate to) {
        return orderMapper.countCancelledByProvider(providerId, from, to);
    }

    @Override
    @Transactional(readOnly = true)
    public Long averageResponseMinutes(long providerId, LocalDate from, LocalDate to) {
        return orderMapper.averageResponseMinutes(providerId, from, to);
    }

    @Override
    @Transactional(readOnly = true)
    public Long averageServiceMinutes(long providerId, LocalDate from, LocalDate to) {
        return orderMapper.averageServiceMinutes(providerId, from, to);
    }
}
