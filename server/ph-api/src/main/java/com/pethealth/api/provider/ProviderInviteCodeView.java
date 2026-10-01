package com.pethealth.api.provider;

import java.time.LocalDateTime;

/**
 * 门店推广码与它的拉新战况，对应 contract 的 {@code ProviderInviteCodeView}。
 *
 * <p>一期验收标准里「商会端标准化与券体系」的第 4 项（邀请任务：任务配置、进度跟踪、奖励发放）
 * 与第 5 项（考核：拉新）的交汇点：**服务者侧第一次有了自己的拉新入口**，
 * 而考核的拉新那一项就是从这些关系里数出来的（ADR-0039 第三节）。
 *
 * <p>{@code code} 为 {@code null} 表示这家店还没生成过推广码——**没有推广码 = 平台侧还没给这家店
 * 拉新入口**，考核的拉新项按「不参与」处理（不是记 0 分）。这个 null 在契约里是明确写着的状态，
 * 不是错误。
 *
 * <p>三个计数是**累计**的（不是当月）：{@code effectiveInvites} 是考核取数的同一个口径
 * （{@code status = 2}，即完成建档且观察期内有行为），{@code pendingInvites} 还在 24 小时观察窗里，
 * {@code invalidInvites} 是被反作弊判据或观察窗内无行为判掉的。三个数加起来就是扫过这个码的人数
 * ——服务者能一眼看出「来了多少、算数多少」。
 */
public record ProviderInviteCodeView(
        String code,
        LocalDateTime createdAt,
        long effectiveInvites,
        long pendingInvites,
        long invalidInvites) {
}
