package com.pethealth.privilege.api;

import com.pethealth.privilege.domain.PointBehavior;

/**
 * 积分对外接口——行为发分与兑换。
 *
 * <p>给谁用：
 *
 * <ul>
 *   <li>{@link #award} —— **行为发生方**调它（打卡在 ph-record、完善档案在 ph-record /
 *       ph-account、邀请在 ph-privilege 内部、评价在 ph-content）。行为方只说
 *       「谁做了什么、引用是什么」，分值与频次由积分模块按配置判定；
 *   <li>{@link #exchange} —— C 端兑换（下一波 app.yaml）；
 *   <li>{@link #balanceOf} —— 展示余额。
 * </ul>
 *
 * <p>三条必须钉死的口径（ADR-0038 第四节）：
 *
 * <ul>
 *   <li><b>积分是另一套账</b>：与权益并列，同一行为可以同时推进权益阶梯计数与积分发分，
 *       但**奖励物不得重复**——所以行为方要么发分、要么发券，不能两个都发；
 *   <li><b>每日上限</b>（默认 20 分）只作用于占上限的行为；邀请与一次性项不占
 *       （否则一个 20 分的邀请奖励会当天把上限吃光）；
 *   <li><b>幂等</b>：{@code (userId, behaviorCode, sourceRef)} 唯一，
 *       重放 / 重试 / 批算重跑只会有一条流水。
 * </ul>
 *
 * <p>实现在 {@code com.pethealth.privilege.service.PointsService}。
 */
public interface PointsApi {

    /**
     * 行为码取值——**定义在 {@link PointBehavior}**（行为码是代码约定，行为方要按名调用），
     * 这里给行为发生方（记录、内容、账号）一套不依赖 {@code privilege.domain} 的名字。
     */
    String BEHAVIOR_CHECK_IN = PointBehavior.CHECK_IN;
    /** 被邀请人奖励（默认停用；见 {@link PointBehavior#INVITE_INVITEE}）。 */
    String BEHAVIOR_INVITE_INVITEE = PointBehavior.INVITE_INVITEE;
    String BEHAVIOR_PROFILE_COMPLETE = PointBehavior.PROFILE_COMPLETE;
    String BEHAVIOR_REVIEW = PointBehavior.REVIEW;
    /**
     * 看了一次 AI 建议（任务中心「查看 AI 建议」的进度来源）。
     *
     * <p>**0 分**（V27 的种子）：只记行为、不发分——任务进度按流水聚合，而这条行为本身不该发分
     * （发了就是替甲方加规则）。生产者是 ph-ai 的 `AiAdviceRecorder`：AI 咨询**真的产出了建议**
     * 才记一次，降级（模型没给出可用结果）不记。
     */
    String BEHAVIOR_AI_ADVICE = PointBehavior.AI_ADVICE;

    /**
     * 记一次行为并按配置发分。
     *
     * <p>不抛异常表达「没发成」：频次用完、达到日上限、重复引用都是**正常的业务结果**，
     * 用 {@link AwardResult#awarded()} 表达，行为方不需要 try/catch 就能继续自己的主流程
     * （发分失败不该让打卡失败）。
     *
     * @param command 行为命令
     * @return 发放结果；{@code reason} 为空表示发成了
     */
    AwardResult award(AwardCommand command);

    /** 当前可用积分。没有账户时返回 0。 */
    int balanceOf(long userId);

    /** 今日（业务日）已获得的、占每日上限的积分。 */
    int todayEarned(long userId);

    /**
     * 兑换：扣分 + 发平台补贴券，在同一个事务里完成（要么都成、要么都不成）。
     *
     * @throws com.pethealth.common.error.BusinessException
     *         <ul>
     *           <li><b>40400</b>：档位不存在或已停用；
     *           <li><b>40900</b>：积分不足（message 里说明还差多少）。
     *         </ul>
     */
    ExchangeResult exchange(long userId, long optionId);

    record AwardCommand(long userId, String behaviorCode, String sourceRef, String remark) {
    }

    /**
     * 发分结果。
     *
     * @param awarded      是否真的发了分（false 时 {@code points} 为 0）
     * @param points       本次发放的分值
     * @param balanceAfter 变动后余额（没发成时是当前余额）
     * @param reason       {@code DUPLICATE} 同一引用已发过分 / {@code DAILY_LIMIT} 今日已达上限 /
     *                     {@code DAILY_COUNT_LIMIT} / {@code MONTHLY_LIMIT} / {@code ONCE_ONLY} 已发过 /
     *                     {@code BEHAVIOR_DISABLED} 行为已停用 / {@code BEHAVIOR_UNKNOWN} 行为码不存在
     */
    record AwardResult(boolean awarded, int points, int balanceAfter, String reason) {
    }

    record ExchangeResult(long couponId, String couponCode, int pointsCost, int balanceAfter) {
    }
}
