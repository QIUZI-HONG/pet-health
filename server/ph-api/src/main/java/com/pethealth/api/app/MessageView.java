package com.pethealth.api.app;

import java.time.LocalDateTime;

/**
 * 消息中心的一条内容，对应 contract/app.yaml 的 {@code MessageView}。
 *
 * <p>「健康提醒」与「业务通知」共用这个结构，靠 {@code kind} 区分——未读、已读、未读角标
 * 是跨两类的统一行为（ADR-0019）。{@code riskLevel} 为红色时前端要把开关置灰（不可关闭）。
 */
public record MessageView(
        Long id,
        Integer kind,
        Integer type,
        String title,
        String content,
        Integer riskLevel,
        LocalDateTime remindAt,
        boolean read,
        LocalDateTime readAt,
        String actionHint,
        String actionTarget,
        Long petId,
        LocalDateTime createdAt) {
}
