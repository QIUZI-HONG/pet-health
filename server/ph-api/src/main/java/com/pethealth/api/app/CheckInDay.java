package com.pethealth.api.app;

import java.util.List;

/**
 * 某一天的打卡状态，对应 contract/app.yaml 的 {@code CheckInDay}。
 *
 * <p>{@code done} 的口径是「任一项有记录」而不是「六项齐全」——桌面 Web 上强制六项会让人放弃
 * （ADR-0018 的取舍）。
 */
public record CheckInDay(
        String date,
        List<CheckInItem> items,
        int completedCount,
        int totalCount,
        boolean done,
        boolean backfilled) {
}
