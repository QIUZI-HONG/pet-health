package com.pethealth.api.order;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 服务者侧的订单列表行，对应 contract/provider.yaml 的 {@code ProviderOrderSummaryView}。
 *
 * <p>**不含核销码**：门店凭用户出示的码定位订单，不需要知道码本身——
 * 不知道就更不可能被记下来复用（ADR-0038 第二节）。
 *
 * <p>{@code userPhone} 是脱敏值（{@code 138****8888}）：手机号在库里是密文（ADR-0013），
 * 列表一律脱敏。门店按完整手机号**搜索**走的是等值查询（查得到、但看不到明文）——
 * 两者的张力 ADR 没裁决，见 ADR-0048 的待澄清。
 *
 * <p>{@code petSpecies}（1 犬 / 2 猫）与 {@code cancelRequestStatus}（1 待门店处理）
 * 是门店最需要一眼看到的两列：前者决定准备什么工位与耗材，后者是**要门店行动的**。
 *
 * <p>{@code estimatedPayAmount} 是**到店按它收款**的金额（券在门店抵扣），
 * 但它不是平台的收款事实——平台不经手资金（ADR-0036）。
 */
public record ProviderOrderSummaryView(
        Long id,
        String orderNo,
        Integer status,
        Long userId,
        String userNickname,
        String userPhone,
        Long petId,
        String petName,
        Integer petSpecies,
        Long serviceId,
        String serviceName,
        LocalDate appointmentDate,
        String startTime,
        String endTime,
        String totalAmount,
        String couponDiscount,
        String estimatedPayAmount,
        Long couponId,
        Integer cancelRequestStatus,
        LocalDateTime createdAt) {
}
