package com.pethealth.api.app;

import java.time.LocalDate;

/**
 * 时间轴的一条事件，对应 contract/app.yaml 的 {@code TimelineEvent}（切片 #102）。
 *
 * <p><b>只收四类</b>（ADR-0030 第四条，本轮拍板）：就医、疫苗/驱虫、**异常**打卡、服务者报工。
 * 正常打卡不进时间轴——一天最多 6 条日常记录会把它淹成流水账，检索价值（「回头找那次就医」）就没了。
 */
public record TimelineEventView(
        Long id,
        /** medical / vaccine / deworm / abnormal / provider */
        String type,
        String typeName,
        Integer category,
        LocalDate date,
        String title,
        /** 一行摘要（取值 / 备注 / 到期状态），没有就是 null */
        String summary,
        Integer source,
        String sourceLabel,
        boolean abnormal,
        boolean backfilled) {
}
