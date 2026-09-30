package com.pethealth.order.service;

import com.pethealth.order.event.OrderReviewedEvent;
import com.pethealth.privilege.api.PointsApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 评价晒单 → 行为发分：{@code point_behavior} 里 {@code REVIEW}（评价晒单 5 分、每月上限 5 次）
 * 此前**没有生产者**，这里是它第一个真实产出路径。
 *
 * <p>与先例 {@code ph-record} 的 {@code CheckInPointsListener} 同一条纪律、同一个理由
 * （见 {@link OrderReviewedEvent} 的类注释）：走 AFTER_COMMIT，不在评价事务里发分——
 * 积分是另一套账，发分失败**不得**让评价失败（分可以补、评价丢了补不回来）。
 *
 * <p>三层结构对应三条刻意选的口径：
 *
 * <ol>
 *   <li><b>返回 {@code awarded=false} 不抛异常</b>（行为被运营停用、本月已满 5 次、
 *       今日上限已到）——{@code award} 的契约就是这样，那是**正常的业务结果**，本类不翻译、不重试；
 *   <li><b>抛异常也不让评价 500</b>：AFTER_COMMIT 的回调发生在提交之后，异常会一路冒到调用方
 *       （{@code triggerAfterCommit} 不吞异常），所以这里必须接住并降级为「这一次不发分」；
 *   <li><b>{@code sourceRef} 用订单 id</b>（{@code review:{orderId}}）：它既是幂等引用
 *       （{@code uk_behavior_ref} 兜底），也与「一单一评」是同一条语义——同一单再发一次分
 *       在业务上不可能发生，真发生了也只会有一条流水。
 * </ol>
 *
 * <p><b>跨模块依赖的形状</b>：{@link PointsApi} 是 ph-privilege 的 {@code api} 包里的接口
 * （实现在它的 {@code service} 包），与 ph-order 已有的 {@code CouponApi} 是同一个包、同一条
 * 依赖方向。它**不是** ph-api 里的端口，所以不需要 {@code @Primary}——
 * 本仓里只有「接口在 ph-api、实现散落在某个模块」的端口才有两个候选的问题。
 */
@Component
public class OrderReviewPointsListener {

    private static final Logger log = LoggerFactory.getLogger(OrderReviewPointsListener.class);

    /** 幂等引用前缀：与行为码一起构成唯一键，在流水里一眼看出这是哪一单的评价。 */
    private static final String SOURCE_PREFIX = "review:";

    private final PointsApi pointsApi;

    public OrderReviewPointsListener(PointsApi pointsApi) {
        this.pointsApi = pointsApi;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderReviewed(OrderReviewedEvent event) {
        String sourceRef = SOURCE_PREFIX + event.orderId();
        try {
            PointsApi.AwardResult result = pointsApi.award(new PointsApi.AwardCommand(
                    event.userId(), PointsApi.BEHAVIOR_REVIEW, sourceRef, null));
            if (!result.awarded()) {
                // 没发成不是错误：本月已满 5 次（MONTHLY_LIMIT）、今日上限已到（DAILY_LIMIT）、
                // 行为被运营停用（BEHAVIOR_DISABLED）都是正常结果——评价本身已经提交成功了
                log.debug("评价未发分：user={} ref={} reason={}", event.userId(), sourceRef, result.reason());
            }
        } catch (RuntimeException e) {
            // 降级：积分是另一套账，发分失败不该让已经提交的评价变成 500。只记日志不抛——
            // 分可以在人工补发时补上，用户写的那句评价丢了就补不回来了
            log.warn("评价发分失败（评价已成功，分数待补）：user={} order={} ref={} 原因={}",
                    event.userId(), event.orderId(), sourceRef, e.toString());
        }
    }
}
