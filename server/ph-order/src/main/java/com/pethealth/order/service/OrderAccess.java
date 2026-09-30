package com.pethealth.order.service;

import com.pethealth.common.error.BusinessException;
import com.pethealth.order.domain.ServiceOrder;
import com.pethealth.order.mapper.ServiceOrderMapper;
import org.springframework.stereotype.Component;

/**
 * 订单归属校验：**本模块只保留一份实现**。
 *
 * <p>原先 {@link OrderService} 与 {@link OrderReviewService} 各写了一份逐字节相同的
 * {@code requireMine}。同一个东西有两个副本时，改了一处漏另一处不会报错——只会让
 * 「不是我的订单」在某条路径上变成另一种答复，而那种不一致没人会去测。
 *
 * <p><b>口径</b>：越权与不存在**同码 40400**（不是 40300）。用 order_id 探测别人的订单时，
 * 「不存在」与「不是你的」返回同一个答复，探测就拿不到任何信息。这条与
 * {@code PetService#requireOwned} 是同一个口径，两个域不要各立一套。
 *
 * <p>软删除的订单查不到：{@code selectById} 受 MyBatis-Plus 逻辑删除约束，
 * 恰好也是我们想要的（删掉的订单不该还能评价 / 取消）。
 */
@Component
public class OrderAccess {

    private final ServiceOrderMapper orderMapper;

    public OrderAccess(ServiceOrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    /**
     * 取订单，并要求它属于 {@code userId}。
     *
     * @return 归属校验通过的订单（非 null）
     * @throws BusinessException 40400：订单不存在，或不是这个用户的
     */
    public ServiceOrder requireMine(long userId, long orderId) {
        ServiceOrder order = orderMapper.selectById(orderId);
        if (order == null || order.getUserId() == null || order.getUserId() != userId) {
            throw BusinessException.notFound("订单不存在");
        }
        return order;
    }
}
