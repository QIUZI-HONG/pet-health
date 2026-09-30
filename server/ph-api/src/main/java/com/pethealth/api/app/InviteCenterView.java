package com.pethealth.api.app;

import java.util.List;

/**
 * 我的邀请码与邀请进度，对应 contract/app.yaml 的 {@code InviteCenterView}。
 *
 * <p>进度按**有效邀请数**算（被邀请人完成建档 **且** 24 小时内有行为，ADR-0046 第二节），
 * 不按注册数——按注册数算的话，同一批刷出来的号当晚就能把阶梯奖领走。
 *
 * <p>四个计数各有用途，缺一个界面就得自己猜：{@code registeredCount} 是「有多少人用了我的码」，
 * {@code pendingCount} 是「还在观察窗里」，{@code effectiveCount} 是「真正算数的」（阶梯与积分都按它），
 * {@code invalidCount} 是「被反作弊拦下或观察窗内没行为的」。
 *
 * <p>{@code codes} 为空数组时前端引导去生成；{@code nextThreshold} / {@code nextRemaining}
 * 五档都达成时为空（不是 0——0 会显示成「还差 0 个」）。
 */
public record InviteCenterView(
        List<InviteCodeView> codes,
        Integer registeredCount,
        Integer pendingCount,
        Integer effectiveCount,
        Integer invalidCount,
        List<InviteLadderProgressView> ladder,
        Integer nextThreshold,
        Integer nextRemaining) {
}
