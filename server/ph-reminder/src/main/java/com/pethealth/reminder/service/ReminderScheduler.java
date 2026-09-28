package com.pethealth.reminder.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 提醒的每日批算（ADR-0019：08:00 Asia/Shanghai）。
 *
 * <p>用 Spring `@Scheduled` 而不是 XXL-JOB——[ADR-0003](../../../../../docs/adr/0003-lean-middleware.md)
 * 去掉了那个中间件，单进程部署下不需要它。
 *
 * <p>只是「主动生成」这一侧的载体：生成结果是站内消息，用户回到站内才看得到（ADR-0019 的口径）。
 * 用户读消息中心时还会惰性补算一次，所以批算失败不会让用户看不到提醒——只会让未读角标晚一点出现。
 */
@Component
public class ReminderScheduler {

    private final ReminderGenerator reminderGenerator;

    public ReminderScheduler(ReminderGenerator reminderGenerator) {
        this.reminderGenerator = reminderGenerator;
    }

    @Scheduled(cron = ReminderGenerator.DAILY_CRON, zone = "Asia/Shanghai")
    public void runDaily() {
        reminderGenerator.generateForAllUsers();
    }
}
