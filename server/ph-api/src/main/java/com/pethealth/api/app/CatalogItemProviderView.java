package com.pethealth.api.app;

/**
 * 做某个项目的门店（C 端「按项目找店」），对应 contract/app.yaml 的
 * {@code CatalogItemProviderView}。
 *
 * <p>与 {@link ProviderSummaryView} **不是同一张 schema**，也不该合并：这张多两样下单必需的东西
 * ——{@code serviceId}（下单要传的就是它）与 {@code price}（**这个项目在这家店的定价**，
 * 同一个项目在不同门店价格不同）；少了 {@code intro} / {@code lng} / {@code lat}
 * （按项目找店时不需要门店简介与坐标，少一个字段就少一处要维护的展示逻辑）。
 *
 * <p>{@code price} 是门店在平台区间内的定价（元，两位小数字符串），下单时服务端会再校验一次
 * （越界 90001）。{@code priceUnit} 来自目录侧，可能为空（目录项被软删）。
 */
public record CatalogItemProviderView(
        Long providerId,
        String providerName,
        Integer type,
        String typeName,
        String rating,
        String address,
        String phone,
        Long serviceId,
        String price,
        String priceUnit) {
}
