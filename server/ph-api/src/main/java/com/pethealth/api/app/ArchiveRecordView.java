package com.pethealth.api.app;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 一条档案分项记录，对应 contract/app.yaml 的 {@code ArchiveRecord}（切片 #102）。
 *
 * <p>它是**原始记录**：{@code payload} 里的内容原样来自用户（或服务者报工 / AI），
 * 平台不做医学判读（CONTEXT.md：平台不产出医疗记录）。
 *
 * <p>{@code authoritative} 是**多源写入的读时口径**（ADR-0030 第三条，本轮拍板）：
 * 证件与防疫以服务者报工为准、自述类以用户为准、**AI 永远不是权威**；
 * 权威来源缺席时次一级来源自然顶上。同一天可能有两条不同来源的记录——**冲突时两条都保留**，
 * 前端把权威那条当「当前值」，其余按时间列出，不做静默覆盖。
 */
public record ArchiveRecordView(
        Long id,
        /** 所属分项编码（metrics / documents / elderly / medical 等），由 category 反查 */
        String section,
        /** 落库分项编码（1 体重 … 12 老年专项），与契约的 section 是两个层次 */
        Integer category,
        LocalDate recordDate,
        String title,
        String value,
        String unit,
        LocalDate dueOn,
        String note,
        boolean abnormal,
        Integer source,
        String sourceLabel,
        boolean authoritative,
        boolean backfilled,
        LocalDateTime createdAt) {
}
