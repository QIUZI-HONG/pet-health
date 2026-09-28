package com.pethealth.api.app;

import java.time.LocalDate;

/**
 * 一条疫苗 / 驱虫记录，对应 contract/app.yaml 的 {@code EpidemicRecord}。
 *
 * <p>{@code daysUntilDue} 由服务端算：负数表示已过期（前端据此换成「已过期 N 天」的说法）。
 */
public record EpidemicRecordView(
        Long id,
        Integer kind,
        String name,
        LocalDate givenOn,
        LocalDate nextDueOn,
        Integer daysUntilDue) {
}
