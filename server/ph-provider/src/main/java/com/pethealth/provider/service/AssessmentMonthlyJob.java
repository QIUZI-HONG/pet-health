package com.pethealth.provider.service;

import com.pethealth.common.time.AppTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;

/**
 * 月度考核批算（F022）：**每月 1 日算上月**（ADR-0039 第三节，交付文档 F022 的「每月 1 日自动计算」）。
 *
 * <p>三条刻意的设计：
 *
 * <ol>
 *   <li><b>Spring {@code @Scheduled}，不引 XXL-JOB</b>（ADR-0003 已去掉那套中间件）：
 *       本项目的批算都是「一天一次、单实例即可」的量级；
 *   <li><b>业务逻辑不在这里</b>：这个类只负责「什么时候算、算哪个账期」，真正的取数与算分在
 *       {@link AssessmentService}（所以测试可以直接调 {@link #run()}，不必等时间）；
 *   <li><b>幂等交给唯一键</b>：重跑、补跑、多实例同时跑都只会有一条
 *       （{@code assessment_monthly_score} 的 {@code (provider_id, period)} 唯一，
 *       撞键的那次忽略）——批算的正确性不押在「只跑一次」上，因为那件事没法保证。
 * </ol>
 *
 * <p>时间点选 04:00：与券过期（01:10）、资质到期（03:30）、月度阶梯（09:00）错开，
 * 免得几个批算在同一时刻抢数据库连接。
 */
@Component
public class AssessmentMonthlyJob {

    private static final Logger log = LoggerFactory.getLogger(AssessmentMonthlyJob.class);

    private final AssessmentService assessmentService;

    public AssessmentMonthlyJob(AssessmentService assessmentService) {
        this.assessmentService = assessmentService;
    }

    /** 每月 1 日 04:00（东八区）算上月。 */
    @Scheduled(cron = "${app.provider.assessment-cron:0 0 4 1 * *}", zone = "Asia/Shanghai")
    public void scheduled() {
        RunResult result = run();
        log.info("月度考核批算完成：账期 {}，写入 {} 家门店的考核分（已算过的账期会被唯一键跳过）",
                result.period(), result.calculated());
    }

    /**
     * 算一次上月（测试与运营手动补跑都走它）。
     *
     * <p>服务者侧只能看**已经算出来的**账期，所以这个入口是「考核什么时候出现」的唯一开关。
     */
    public RunResult run() {
        YearMonth lastMonth = YearMonth.from(AppTime.today()).minusMonths(1);
        int calculated = assessmentService.calculateMonth(lastMonth);
        return new RunResult(lastMonth.toString(), calculated);
    }

    /**
     * 一次批算的结果。
     *
     * @param period     账期（yyyy-MM，上月）
     * @param calculated 本次真正写入的门店数（已经算过的不会重复写）
     */
    public record RunResult(String period, int calculated) {
    }
}
