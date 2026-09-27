package com.pethealth.api.app;

/** 打卡的某一项，对应 contract/app.yaml 的 {@code CheckInItem}。 */
public record CheckInItem(
        int category,
        String name,
        boolean filled,
        boolean abnormal,
        String value,
        String note,
        boolean backfilled) {
}
