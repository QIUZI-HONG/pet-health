package com.pethealth.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 订单模块的可调项（ADR-0010：业务可调项要有配置入口）。
 *
 * @param appointmentReminderHours 预约开始前**几小时**提醒用户（默认 2）
 */
@ConfigurationProperties(prefix = "app.order")
public record OrderProperties(int appointmentReminderHours) {

    public OrderProperties {
        appointmentReminderHours = appointmentReminderHours <= 0 ? 2 : appointmentReminderHours;
    }
}
