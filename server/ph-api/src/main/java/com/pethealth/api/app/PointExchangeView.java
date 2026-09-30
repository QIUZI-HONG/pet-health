package com.pethealth.api.app;

import com.pethealth.api.privilege.CouponDtos;

/**
 * 兑换结果，对应 contract/app.yaml 的 {@code PointExchangeView}。
 *
 * <p>扣分与发券在**同一个事务**里：要么都成、要么都不成（ADR-0046 第六节），
 * 所以这里能一次给出扣分后的余额与发到手里的那张券。
 *
 * <p>兑换只兑**平台补贴券**：成本归平台，不消耗服务者的贡献额度（ADR-0038 第四节）。
 */
public record PointExchangeView(
        Integer pointsCost,
        Integer balanceAfter,
        CouponDtos.CouponView coupon) {
}
