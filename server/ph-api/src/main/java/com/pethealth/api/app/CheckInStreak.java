package com.pethealth.api.app;

/**
 * 连续打卡天数，对应 contract/app.yaml 的 {@code CheckInStreak}。
 *
 * <p>今天还没打卡**不算断签**：昨天有记录就仍然连续，今天补上即可。
 * 断签归零的判定只在「昨天与今天都没有记录」时发生。
 */
public record CheckInStreak(
        int streakDays,
        boolean checkedToday,
        int longestStreakDays) {
}
