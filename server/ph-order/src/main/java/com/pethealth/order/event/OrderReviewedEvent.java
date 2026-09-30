package com.pethealth.order.event;

/**
 * 订单已被评价（领域事件）。
 *
 * <p>为什么要有事件而不是在同一次调用里发分：评价是主流程，发 REVIEW 分是另一套账
 * （ADR-0038 第四节）。在评价的事务里直接调 {@code PointsApi.award} 会得到两个都不想要的后果：
 *
 * <ul>
 *   <li>发分抛异常 → 事务被标记 rollback-only → **评价也回滚**（用户写的那句话白填了）；
 *   <li>发分成功但评价随后回滚 → 分留下、评价没了（比前者更难查）。
 * </ul>
 *
 * <p>所以与打卡发分完全同一条纪律（先例 {@code CheckInPointsListener}）：在评价提交**之后**
 * 处理（{@code TransactionalEventListener} 的 AFTER_COMMIT），发分失败只记日志。
 *
 * @param userId  评价人（积分账是用户的，不是宠物的）
 * @param orderId 被评价的订单 id——幂等引用 {@code review:{orderId}} 由它构成，
 *                一单一评的语义与唯一键同时落在「一次评价 = 一条流水」上
 */
public record OrderReviewedEvent(
        long userId,
        long orderId) {
}
