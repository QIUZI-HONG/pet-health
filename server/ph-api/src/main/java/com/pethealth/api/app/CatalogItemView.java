package com.pethealth.api.app;

/**
 * 标准目录项（C 端），对应 contract/app.yaml 的 {@code CatalogItemView}。
 *
 * <p>{@code priceMin} / {@code priceMax} 是**平台允许的定价区间**，不是某家店的报价：
 * 各家店在区间内自行定价（ADR-0034 第三节），所以「这个项目多少钱」要在门店维度看
 * （门店详情或 {@code GET /catalog/items/{code}/providers}）。这一条在界面上必须说清楚，
 * 不然区间价会被当成「起价」。
 *
 * <p>{@code durationMinutes} 为 {@code null} 表示目录项没填参考时长——**不是 0 分钟**。
 */
public record CatalogItemView(
        String code,
        String categoryCode,
        String categoryName,
        String name,
        String description,
        String priceMin,
        String priceMax,
        String priceUnit,
        Integer durationMinutes,
        Integer applicablePets) {
}
