package com.pethealth.privilege.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 月度阶梯批算的**触发**（F018）：只负责「到点叫一次」，规则全在 {@link MonthlyLadderJob}。
 *
 * <p>为什么触发要单独一个 bean，而不是把 {@code @Scheduled} 加在 {@link MonthlyLadderJob#run()} 上：
 * 那个方法带 {@code @Transactional}，而被**同一个类**里的方法调用时 Spring 的代理不参与
 * ——事务会静默失效（批算变成逐条自动提交），**而测试是注入 Bean 调的 {@code run()}，
 * 走的是代理、有事务**：于是「测试全绿、生产少一层保护」这种最难发现的分叉就出现了。
 * 触发分居两个 bean 之后，两条路径都经过代理，行为一致。
 *
 * <p>本仓既有同类先例：{@code ReminderScheduler}（叫 {@code ReminderGenerator}）与
 * {@code HealthReportScheduler}（叫 {@code HealthReportService}）。结构化护栏见
 * {@code ScheduledTransactionBoundaryTest}——「一个类里不同时出现 @Scheduled 与 @Transactional」。
 */
@Component
public class MonthlyLadderScheduler {

    private static final Logger log = LoggerFactory.getLogger(MonthlyLadderScheduler.class);

    private final MonthlyLadderJob job;

    public MonthlyLadderScheduler(MonthlyLadderJob job) {
        this.job = job;
    }

    /** 每月 1 日 09:00 算上月（与报告、提醒的批算错开时间，避免同一时刻抢连接）。 */
    @Scheduled(cron = "${app.privilege.monthly-ladder-cron:0 0 9 1 * *}", zone = "Asia/Shanghai")
    public void scheduled() {
        MonthlyLadderJob.SweepResult result = job.run();
        log.info("月度阶梯批算完成：账期 {}，扫描 {} 人，发券 {} 张（档位为空时这一行会一直是 0）",
                result.period(), result.scanned(), result.granted());
    }
}
