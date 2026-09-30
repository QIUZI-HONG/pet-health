package com.pethealth.api.app;

import java.math.BigDecimal;

/**
 * 报告的汇总数字，对应 contract/app.yaml 的 {@code HealthReportStats}（切片 #115）。
 *
 * <p>**全部来自 {@code health_score} 按天的行与 {@code archive_record}**（本模块自己的表，
 * ADR-0006 允许），加上**经接口**取到的 AI 咨询计数（ph-ai 的只读接口，不 join 它的表）。
 *
 * <p>「没有数据」与「0」是两件事：没有评分时 {@code avgTotal} 是 {@code null}，
 * 而 {@code recordDays} 可以是 0——前端据此显示「这一期还没有记录」而不是「0 分」。
 */
public record HealthReportStats(
        int recordDays,
        /** 周期总天数：完成度的分母，让前端不必自己算闰月与星期 */
        int periodDays,
        int abnormalCount,
        Integer avgTotal,
        Integer avgBehavior,
        Integer avgHygiene,
        Integer prevAvgTotal,
        Integer prevAbnormalCount,
        BigDecimal weightMin,
        BigDecimal weightMax,
        int aiConsults,
        int aiRedFlags,
        int providerRecords) {
}
