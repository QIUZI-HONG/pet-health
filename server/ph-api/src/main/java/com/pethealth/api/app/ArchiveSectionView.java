package com.pethealth.api.app;

/**
 * 一个档案分项的入口，对应 contract/app.yaml 的 {@code ArchiveSection}（切片 #102）。
 *
 * <p>**8 个分项与评分五维不同构**（CONTEXT.md）：分项是「录进去的」、维度是「算出来的」，
 * 所以这里没有任何分数字段——分数在 {@link HealthScoreView} 里。
 * 两者多对多的映射写在 ADR-0030 的权威表里，代码里由 {@code ArchiveSection} 维护。
 */
public record ArchiveSectionView(
        String code,
        String name,
        String description,
        /** records = 内容在本模块的档案表；files = 内容在文件模块（照片视频） */
        String contentSource,
        /** 是否可用 {@code POST /archive-records} 录入；打卡六项、防疫、照片视频为 false（各有自己的入口） */
        boolean recordable,
        /** 当前是否可用。**只有「老年专项」会为 false**（专项照护未开启，见 ADR-0032） */
        boolean enabled,
        String disabledReason,
        /** 已录条数；照片视频分项为 null（条数要从文件接口取，本模块不碰 file_object） */
        Integer recordCount,
        String latestDate) {
}
