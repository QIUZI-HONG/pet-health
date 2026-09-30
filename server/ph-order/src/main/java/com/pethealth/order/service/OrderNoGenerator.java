package com.pethealth.order.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pethealth.common.time.AppTime;
import com.pethealth.order.domain.ServiceOrder;
import com.pethealth.order.mapper.ServiceOrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 订单号：{@code PH} + 业务日 + 当日序号（如 {@code PH20261001001}）。
 *
 * <p>格式由项目所有者拍板（ADR-0049 §五）：`PH` 是 pet-health；**不沿用交付文档的 `TL`**
 * （那个前缀来历不明）。业务日取 {@link AppTime#today()}（东八区，不是 JVM 默认时区）。
 *
 * <p><b>正确性在数据库唯一键，不在这里</b>：{@code order.uk_order_no} 是唯一的兜底，
 * 撞了就换一个号重试（{@link #next()} 每次调用都会前进一格）。所以本类**不做**
 * 「先查当天最大序号再加一」——那是并发下必然重号的写法（本项目在 D9 / D19 踩过同类）。
 * 这里唯一的数据库读是「给当天的内存计数器**播种**」：取当天已有订单数当起点，
 * 让重启后的进程不去从 001 硬撞已存在的那些号。种子偏低只会多几次重试，不会让号重复。
 *
 * <p>序号超过 999 时不再补零（`PH202610011000`）：宁可号变长，也不让第 1000 张单下不出去。
 * 那种量级该由运营决定要不要扩位（见 ADR-0048 的待澄清）。
 */
@Component
public class OrderNoGenerator {

    private static final Logger log = LoggerFactory.getLogger(OrderNoGenerator.class);

    /** 序号位数：{@code 001}。 */
    private static final int SEQUENCE_WIDTH = 3;

    /** 一天的订单数上限的软提示（超过它只是号变长，不是错误）。 */
    private static final int SEQUENCE_SOFT_LIMIT = 999;

    private static final DateTimeFormatter BUSINESS_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ServiceOrderMapper orderMapper;

    /** 每个业务日一个计数器；进程内并发靠 {@link AtomicInteger}，跨进程靠唯一键 + 重试。 */
    private final Map<LocalDate, AtomicInteger> counters = new ConcurrentHashMap<>();

    public OrderNoGenerator(ServiceOrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    /** 下一个订单号（每次调用都前进一格，重试时直接再调一次）。 */
    public String next() {
        LocalDate today = AppTime.today();
        AtomicInteger counter = counters.computeIfAbsent(today, this::seed);
        int sequence = counter.incrementAndGet();
        if (sequence == SEQUENCE_SOFT_LIMIT + 1) {
            log.warn("当天的订单序号已超过 {} 位：号会变长（PH{}+{}），该由运营决定要不要扩位",
                    SEQUENCE_WIDTH, today.format(BUSINESS_DAY), sequence);
        }
        return ServiceOrder.ORDER_NO_PREFIX + today.format(BUSINESS_DAY)
                + String.format("%0" + SEQUENCE_WIDTH + "d", sequence);
    }

    /**
     * 给当天播种：当天已有的订单数。
     *
     * <p>刻意用 {@code COUNT} 而不是 {@code MAX(序号)+1}：计数偏低是安全的（唯一键会拦下重复并重试），
     * 而解析序号串去求最大值会把一个「只该是提示」的值变成一条隐式契约。
     */
    private AtomicInteger seed(LocalDate day) {
        String prefix = ServiceOrder.ORDER_NO_PREFIX + day.format(BUSINESS_DAY);
        Long existing = orderMapper.selectCount(Wrappers.<ServiceOrder>lambdaQuery()
                .likeRight(ServiceOrder::getOrderNo, prefix));
        int start = existing == null ? 0 : existing.intValue();
        log.debug("订单号计数器播种：{} → {}", prefix, start);
        return new AtomicInteger(start);
    }
}
