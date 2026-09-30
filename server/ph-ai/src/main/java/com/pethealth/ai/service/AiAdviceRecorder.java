package com.pethealth.ai.service;

import com.pethealth.common.time.AppTime;
import com.pethealth.privilege.api.PointsApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 一次咨询**真的产出了建议** → 记一次 {@code AI_ADVICE} 行为（0 分，只推进任务中心「查看 AI 建议」的进度）。
 *
 * <p>为什么要有这个类（而不是让 {@link AiConsultService} 直接调 {@code PointsApi}）：
 * 「记行为失败绝不能影响咨询」这条口径需要一个**只做降级、不做别的事**的地方——
 * 它把 {@code PointsApi} 的「不抛异常表达没发成」之外的那点风险（连不上库、余额锁超时之类的
 * {@code RuntimeException}）接住，调用方看到的方法不抛任何东西。与 ph-record 的
 * {@code CheckInPointsListener} 是同一条纪律、同一种写法。
 *
 * <p><b>哪些路径该记由调用方决定</b>（它才看得见 {@code degraded} / {@code red_flag_hits}）：
 * 本类只负责「记一次」。{@link AiConsultService} 的口径是**只要不是降级就记**——
 * 包括红线短路，理由写在那个调用点上。
 *
 * <p>幂等：同一用户同一业务日只记一次（引用的拼法参考 {@code CheckInPointsListener}：
 * {@code ai-advice:2026-09-30}）。它同时是「每日 1 次」的实现与
 * {@code (user_id, behavior_code, source_ref)} 唯一键的落点——当天再咨询几次都是重复引用，
 * {@code award} 会返回 {@code DUPLICATE}，那是正常结果不是错误。
 */
@Component
public class AiAdviceRecorder {

    private static final Logger log = LoggerFactory.getLogger(AiAdviceRecorder.class);

    /** 幂等引用的前缀：与行为码一起构成唯一键，便于在流水里一眼看出这是哪一次咨询。 */
    private static final String SOURCE_PREFIX = "ai-advice:";

    private final PointsApi pointsApi;

    public AiAdviceRecorder(PointsApi pointsApi) {
        this.pointsApi = pointsApi;
    }

    /**
     * 记一次（0 分）。**不抛异常**：没发成与抛异常都只是少一次任务进度，咨询结果照常返回。
     *
     * @param userId 提问的人（行为按用户算，不按宠物：与打卡发分同口径）
     */
    public void record(long userId) {
        String sourceRef = SOURCE_PREFIX + AppTime.today();
        try {
            PointsApi.AwardResult result = pointsApi.award(new PointsApi.AwardCommand(
                    userId, PointsApi.BEHAVIOR_AI_ADVICE, sourceRef, null));
            if (!result.awarded()) {
                // 不是错误：今天已经记过（DUPLICATE）、行为被运营停用（BEHAVIOR_DISABLED）都是正常结果。
                // debug 而不是 warn：一天里的第 2..N 次咨询都会走到这里，info 级别会把日志刷满
                log.debug("AI 建议未记行为：user={} ref={} reason={}", userId, sourceRef, result.reason());
            }
        } catch (RuntimeException e) {
            // 降级：咨询已经成功、回答在用户手里了，任务进度丢了可以补（下一次咨询或人工补记），
            // 为了记一次 0 分的行为把一次成功的咨询变成 500 是本末倒置（同 CheckInPointsListener 的取舍）
            log.warn("记 AI 建议行为失败（咨询已成功，任务进度待补）：user={} ref={} 原因={}",
                    userId, sourceRef, e.toString());
        }
    }
}
