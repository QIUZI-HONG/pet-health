package com.pethealth.api.privilege;

import com.pethealth.api.privilege.CouponDtos.CouponView;

import java.util.Collection;
import java.util.Map;

/**
 * 券实例的**只读**查询接口（跨模块）：订单侧要渲染「本单锁定的那张券」。
 *
 * <p>为什么需要它：{@code OrderView.coupon} / {@code ProviderOrderView.coupon} 是契约里
 * 已经声明的 {@code CouponView}（面额、门槛、有效期、状态、核销时间…），而写侧接口
 * {@link CouponApi} 只回业务动作需要的那个子集（{@code CouponInfo}）——
 * 拿它拼出来的券卡片会缺掉「还能不能用、什么时候到期」这两个用户最关心的字段。
 *
 * <p><b>实现由 ph-privilege 提供（本次交付未接线，见 ADR-0048 的「需要协调」）</b>：
 * 直接转给已有的 {@code CouponMapper} + {@code PrivilegeViews} 即可（C 端的「我的券」列表
 * 需要的是同一段查询）。在它落地之前，订单详情里的券卡片会为空——而不是显示一张
 * 面额、有效期都不对的券（错的那张卡片比缺的那张更坏）。
 *
 * <p>与 {@link CouponApi} 的分工：**写动作（锁 / 释放 / 核销）走 {@code CouponApi}，
 * 读形状走这里**。两个接口都不允许调用方碰 {@code coupon} 表（ADR-0006）。
 */
public interface CouponFactsApi {

    /**
     * 按 id 批量取券实例视图。
     *
     * @param couponIds 券实例 id；空集合不查库
     * @return id → 视图；**查不到的 id 不会出现在 Map 里**（券不会被物理删除，
     *         查不到只可能是入参不对，调用方按「没有这张券」处理）
     */
    Map<Long, CouponView> couponsOf(Collection<Long> couponIds);
}
