package com.pethealth.api.app;

import java.time.LocalDateTime;

/**
 * 邀请阶梯的一档进度，对应 contract/app.yaml 的 {@code InviteLadderProgressView}。
 *
 * <p>**奖励物没配也是有效状态**：档次照记达成，只是 {@code rewardDesc} 为空
 * （ADR-0046 第五节）——没配奖励不代表用户没达成，把两件事混在一起会让阶梯显示得忽明忽暗。
 *
 * <p>{@code achievedAt} 是**达成时间**（有效邀请数首次达到门槛的那一刻），不是结算时间。
 */
public record InviteLadderProgressView(
        Integer threshold,
        boolean achieved,
        LocalDateTime achievedAt,
        String rewardDesc) {
}
