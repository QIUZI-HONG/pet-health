package com.pethealth.api.order;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 订单列表行，对应 contract/app.yaml 的 {@code OrderSummaryView}。
 *
 * <p>**不含核销码**：它只出现在详情里，而且要到预约时间才可见（ADR-0038 第二节）——
 * 到店凭证放进列表等于把凭证挂在外面。
 *
 * <p>三个金额都是**字符串形式的两位小数**（ADR-0011：小数在 JS 里会丢精度），
 * 且都是展示值：{@code estimatedPayAmount = totalAmount − couponDiscount}，
 * **不是收款事实**——本项目钱在门店直接付给服务者（ADR-0036）。
 */
public record OrderSummaryView(
        Long id,
        String orderNo,
        Integer status,
        Long providerId,
        String providerName,
        Long serviceId,
        String serviceName,
        Long petId,
        String petName,
        LocalDate appointmentDate,
        String startTime,
        String endTime,
        String totalAmount,
        String couponDiscount,
        String estimatedPayAmount,
        LocalDateTime createdAt) {
}
