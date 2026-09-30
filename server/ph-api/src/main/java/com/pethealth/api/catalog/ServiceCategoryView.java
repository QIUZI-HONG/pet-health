package com.pethealth.api.catalog;

import java.time.LocalDateTime;

/**
 * 标准服务目录分类，对应 contract 的 {@code ServiceCategoryView}。
 *
 * <p>六个分类是交付文档 5.2 的分类清单（医院 / 洗护美容 / 训犬 / 寄养上门 / 食品用品 / 间接服务），
 * 平台运营可增改。{@code itemCount} 只统计**启用**的目录项：停用项不计入，
 * 否则运营会看到「分类下有 20 项」却只列出 18 项。
 */
public record ServiceCategoryView(
        Long id,
        String code,
        String itemCodePrefix,
        String name,
        String icon,
        String description,
        Integer sortOrder,
        Integer status,
        long itemCount,
        LocalDateTime updatedAt) {
}
