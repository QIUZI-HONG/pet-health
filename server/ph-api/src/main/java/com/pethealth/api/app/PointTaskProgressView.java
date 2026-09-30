package com.pethealth.api.app;

/**
 * 一个任务的进度，对应 contract/app.yaml 的 {@code PointTaskProgressView}。
 *
 * <p>进度**按行为流水聚合**：{@code currentCount} 是当前周期内该行为的记录数，
 * 达标与否是算出来的（ADR-0046 第七节：任务表里不存进度，否则进度有两个来源）。
 *
 * <p>{@code points} 是该行为的单次分值——**任务本身不额外发分**（ADR-0038 第四节：
 * 同一行为发两次奖励正是要被钉死的规则），所以这里给的是「做这件事值多少分」，
 * 而不是「完成这个任务再送多少分」。
 */
public record PointTaskProgressView(
        Long id,
        String code,
        String name,
        Integer period,
        String behaviorCode,
        String behaviorName,
        Integer targetCount,
        Integer currentCount,
        boolean completed,
        Integer points) {
}
