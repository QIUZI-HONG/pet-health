package com.pethealth.order.service;

import com.pethealth.order.config.OrderProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 预约提醒的**触发**（F026）：只负责到点叫一次，规则全在 {@link AppointmentReminderJob}。
 *
 * <p>为什么触发要单独一个 bean：{@code @Scheduled} 方法不能与它要调的
 * {@code @Transactional} 方法同处一个类——**自调用绕过 Spring 的事务代理**，
 * 那一层事务在生产会静默消失，而测试是注入 Bean 调的（有事务）。
 * 本仓既有同类先例（{@code ReminderScheduler} / {@code HealthReportScheduler}），
 * 结构化护栏见 {@code ScheduledTransactionBoundaryTest}。
 */
@Component
public class AppointmentReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(AppointmentReminderScheduler.class);

    private final AppointmentReminderJob job;
    private final OrderProperties properties;

    public AppointmentReminderScheduler(AppointmentReminderJob job, OrderProperties properties) {
        this.job = job;
        this.properties = properties;
    }

    /** 每小时整点跑一次（提醒窗口以小时为单位，再密一点只是重复扫同一批）。 */
    @Scheduled(cron = "${app.order.appointment-reminder-cron:0 0 * * * *}", zone = "Asia/Shanghai")
    public void scheduled() {
        int sent = job.remind();
        if (sent > 0) {
            log.info("预约提醒发出 {} 条（窗口 {} 小时内）", sent, properties.appointmentReminderHours());
        }
    }
}
