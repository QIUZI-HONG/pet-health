package com.pethealth.api.app;

/**
 * 标准目录的分类（C 端），对应 contract/app.yaml 的 {@code CatalogCategoryView}。
 *
 * <p>为什么不复用运营侧的 {@code ServiceCategoryView}：那张带 {@code id} / {@code status} /
 * {@code itemCodePrefix} / {@code sortOrder}，全是内部字段——C 端要不要看一个分类，
 * 由服务端按「启用中」筛过就定了，再下发 {@code status} 只会让前端有机会把它当成第二个判据
 * （与 {@link ProviderSummaryView} 不下发门店状态是同一条口径）。
 *
 * <p>{@code itemCount} 是该分类下**启用中**的目录项数：停用的项目不对外开放，算进去会虚高。
 */
public record CatalogCategoryView(
        String code,
        String name,
        String description,
        String icon,
        Long itemCount) {
}
