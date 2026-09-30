package com.pethealth.record.service;

import com.pethealth.privilege.api.PointsApi;
import com.pethealth.record.event.CheckInSubmittedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 打卡 → 行为发分：接线点（ADR-0046 的「需要协调」第 3 条）。
 *
 * <p><b>为什么是「提交之后」而不是打卡事务里的一次普通调用</b>：打卡是主流程，发分是另一套账
 * （ADR-0038 第四节）。在同一个事务里调 {@code PointsApi.award} 会得到两个都不想要的后果：
 *
 * <ul>
 *   <li>发分抛异常 → 事务被标记 rollback-only → **打卡也回滚**（用户填的六项白填）；
 *   <li>发分成功但打卡随后回滚 → 分留下、记录没了（比前者更难查）。
 * </ul>
 *
 * <p>所以走 {@link TransactionalEventListener} 的 {@code AFTER_COMMIT}：打卡提交成功才发分，
 * 发分失败只记日志（分可以补、打卡不能丢）。与提醒模块的 {@code CheckInRecordedListener}
 * 是同一条纪律。
 *
 * <p>三层结构对应三条刻意的口径：
 *
 * <ol>
 *   <li><b>返回 {@code awarded=false} 不抛异常</b>（频次用完、达到日上限、今天已经打过卡）——
 *       {@code award} 的契约就是这样，本类不做翻译、不重试：那是**正常的业务结果**；
 *   <li><b>抛异常也不让打卡 500</b>：AFTER_COMMIT 的回调发生在提交之后，
 *       异常会一路冒到调用方（{@code triggerAfterCommit} 不吞异常），所以这里必须接住并降级——
 *       降级行为就是「这一次不发分」，流水可以在下一次行为或人工补发时补上；
 *   <li><b>{@code sourceRef} 用提交的业务日</b>（{@code checkin:2026-09-30}）：它既是幂等引用
 *       （{@code uk_behavior_ref} 兜底），也是「每日 1 次」的实现。**不用请求里的 date**：
 *       补录 7 天会变成 7 次发分（一次补录把日上限绕过去），而「打卡」这个行为发生的时间
 *       就是提交的那一刻。
 * </ol>
 */
@Component
public class CheckInPointsListener {

    private static final Logger log = LoggerFactory.getLogger(CheckInPointsListener.class);

    /** 幂等引用的前缀：与行为码一起构成唯一键，便于在流水里一眼看出这是哪一次打卡。 */
    private static final String SOURCE_PREFIX = "checkin:";

    private final PointsApi pointsApi;

    public CheckInPointsListener(PointsApi pointsApi) {
        this.pointsApi = pointsApi;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCheckInSubmitted(CheckInSubmittedEvent event) {
        String sourceRef = SOURCE_PREFIX + event.businessDate();
        try {
            PointsApi.AwardResult result = pointsApi.award(new PointsApi.AwardCommand(
                    event.userId(), PointsApi.BEHAVIOR_CHECK_IN, sourceRef, null));
            if (!result.awarded()) {
                // 没发成不是错误：今天已经打过卡（DUPLICATE / DAILY_COUNT_LIMIT）、
                // 行为被运营停用（BEHAVIOR_DISABLED）都是正常结果。打卡本身已经提交成功了
                log.debug("打卡未发分：user={} ref={} reason={}", event.userId(), sourceRef, result.reason());
            }
        } catch (RuntimeException e) {
            // 降级：积分是另一套账，发分失败不该让已经提交的打卡变成 500（ADR-0046 的同一条纪律）。
            // 只记日志不抛——分可以在下一次打卡或人工补发时补上，打卡记录丢了就补不回来了
            log.warn("打卡发分失败（打卡已成功，分数待补）：user={} pet={} ref={} 原因={}",
                    event.userId(), event.petId(), sourceRef, e.toString());
        }
    }
}
