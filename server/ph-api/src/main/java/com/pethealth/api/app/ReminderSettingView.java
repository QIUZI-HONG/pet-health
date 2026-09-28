package com.pethealth.api.app;

/**
 * 一类提醒的开关状态，对应 contract/app.yaml 的 {@code ReminderSetting}。
 *
 * <p>三个字段要一起看：{@code enabled} 是用户的意愿，{@code platformEnabled} 是平台的总开关，
 * {@code closable} 是「允不允许关」——红色等级的健康提醒 {@code closable=false}（ADR-0019：
 * 用户安全 > 体验）。前端必须把不可关闭的那类置灰并说明原因，而不是让用户点了没反应。
 */
public record ReminderSettingView(
        Integer type,
        String name,
        boolean enabled,
        boolean platformEnabled,
        boolean closable) {
}
