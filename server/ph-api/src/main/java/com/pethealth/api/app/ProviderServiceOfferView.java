package com.pethealth.api.app;

/**
 * 某家店的一个**在架**服务项（C 端详情页的 {@code services}），
 * 对应 contract/app.yaml 的 {@code ProviderServiceOfferView}。
 *
 * <p>与服务者侧的 {@code ProviderServiceView} **不是同一张 schema**，也不该合并：
 * 那张带 {@code status} / {@code rejectReason} / {@code submittedAt}（审核流程的内部状态），
 * C 端一个都不需要。两张 schema 各自演进，改审核流程不会牵动 C 端的生成物。
 *
 * <p>名称、计价单位、耗时都**来自标准目录现取**（不是下单时的快照，ADR-0034 决定 9）：
 * 平台给目录项改名，这里立刻跟着变。目录项被删（软删）时名称相关字段为空，
 * 但服务项本身仍在架——前端要能显示「服务项已下架/名称缺失」而不是崩掉。
 *
 * <p>{@code price} 是**这家店的定价**（元，两位小数字符串），在平台的区间内
 * （ADR-0034 第三节）；下单时服务端还会再校验一次（越界回 90001）。
 *
 * <p>{@code durationMinutes} 预计耗时，来自目录侧；为 {@code null} 表示目录项没填。
 */
public record ProviderServiceOfferView(
        Long id,
        String serviceCode,
        String serviceName,
        String categoryCode,
        String categoryName,
        String price,
        String priceUnit,
        Integer durationMinutes) {
}
