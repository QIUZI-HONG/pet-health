package com.pethealth.api.app;

/**
 * 健康评分的一维，对应 contract/app.yaml 的 {@code HealthScoreDimension}。
 *
 * <p>{@code detail} 是刻意留的：评分必须可解释——用户看到 62 分时要知道
 * 「是因为近 7 天只记录了 2 天」，而不是怀疑宠物病了（ADR-0018）。
 */
public record HealthScoreDimension(
        String key,
        String name,
        Integer score,
        boolean included,
        String excludedReason,
        String detail) {
}
