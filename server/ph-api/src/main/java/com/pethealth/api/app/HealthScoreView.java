package com.pethealth.api.app;

import java.time.LocalDate;
import java.util.List;

/**
 * 当日健康评分，对应 contract/app.yaml 的 {@code HealthScore}。
 *
 * <p>{@code totalScore} 为 null 表示一条记录都没有——前端显示「还没有评分」，
 * **不要显示 0 分**（0 分会被读成「健康状况极差」）。
 */
public record HealthScoreView(
        Integer totalScore,
        String grade,
        List<HealthScoreDimension> dimensions,
        List<TrendPoint> trend,
        LocalDate calcDate,
        String disclaimer) {

    /** 趋势上的一个点。 */
    public record TrendPoint(LocalDate date, int totalScore) {
    }
}
