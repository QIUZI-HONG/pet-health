package com.pethealth.api.app;

/**
 * 一条服务推荐，对应 contract/app.yaml 的 {@code ServiceRecommendationItem}。
 *
 * <p>{@code reason} 是**服务端拼好的整句**（「因为你说腹泻，建议先做常见病诊疗」）：
 * 理由要说得出「为什么是它」，就得把症状与项目名一起说——让各端自己拼会把同一句话拼出三种说法。
 *
 * <p>{@code priceMin} / {@code priceMax} 是平台区间价，不是门店报价（同 {@link CatalogItemView}）。
 */
public record ServiceRecommendationItem(
        String itemCode,
        String categoryCode,
        String categoryName,
        String name,
        String priceMin,
        String priceMax,
        String priceUnit,
        Integer durationMinutes,
        String reason) {
}
