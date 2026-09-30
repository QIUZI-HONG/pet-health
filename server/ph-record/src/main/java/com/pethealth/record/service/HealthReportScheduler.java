package com.pethealth.record.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 健康报告的定时生成（切片 #115，决策见 ADR-0031）：
 * **每周一生成上周的周报、每月 1 日生成上月的月报**。
 *
 * <p>与提醒体系共用同一套机制（Spring {@code @Scheduled}，ADR-0003 已去掉 XXL-JOB），
 * 但**不是同一个类**：报告属档案域，提醒属提醒模块。两条 cron 错开 10 分钟（08:10 / 08:20，
 * 提醒批算是 08:00），避免同一时刻抢连接。
 *
 * <p>生成是**幂等**的（唯一键 + {@code ON DUPLICATE KEY UPDATE id = id}），所以：
 *
 * <ul>
 *   <li>重复跑（多实例、手动补跑）不会出重复报告；
 *   <li>停机错过一次也不会永久缺一期——用户读报告列表时会**惰性补齐**最近一个完整周期
 *       （{@code HealthReportService.list} 里那段）。
 * </ul>
 */
@Component
public class HealthReportScheduler {

    /** 每天 08:10（周一）生成周报；月报那条只在每月 1 日命中。 */
    static final String WEEKLY_CRON = "0 10 8 * * MON";
    static final String MONTHLY_CRON = "0 20 8 1 * *";

    private final HealthReportService healthReportService;

    public HealthReportScheduler(HealthReportService healthReportService) {
        this.healthReportService = healthReportService;
    }

    /**
     * 周报：每周一 08:10。
     *
     * <p>为什么在周一：周期是「上一个完整自然周」，周一才刚好凑齐一周的数据。
     */
    @Scheduled(cron = WEEKLY_CRON, zone = "Asia/Shanghai")
    public void generateWeekly() {
        healthReportService.generateForAllPets();
    }

    /** 月报：每月 1 日 08:20（同一个方法会把两种周期都补齐，幂等所以重复调用无害）。 */
    @Scheduled(cron = MONTHLY_CRON, zone = "Asia/Shanghai")
    public void generateMonthly() {
        healthReportService.generateForAllPets();
    }
}
