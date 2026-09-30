package com.pethealth.api.catalog;

import java.time.LocalDateTime;

/**
 * 标准目录项（含价格区间），对应 contract 的 {@code ServiceItemView}。
 *
 * <p>{@code priceMin} / {@code priceMax} 是**字符串**：小数在 JS 里会丢精度，全局 Jackson 把
 * BigDecimal 写成字符串（ADR-0011）。服务者侧拿到区间后要展示成「价格须在 ¥X–¥Y 之间」，
 * 所以区间与文案一起给出，不让前端自己拼。
 *
 * <p>{@code categoryName} 是拼进来的：界面几乎总是「分类 + 项目名」一起出现，
 * 让每个调用方各查一次分类表不值。
 */
public record ServiceItemView(
        Long id,
        String code,
        String categoryCode,
        String categoryName,
        String name,
        String priceMin,
        String priceMax,
        String priceUnit,
        Integer durationMinutes,
        Integer applicablePets,
        String description,
        Integer sortOrder,
        Integer status,
        LocalDateTime updatedAt) {
}
