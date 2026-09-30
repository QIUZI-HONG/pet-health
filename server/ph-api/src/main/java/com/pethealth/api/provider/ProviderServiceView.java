package com.pethealth.api.provider;

import java.time.LocalDateTime;

/**
 * 服务者上架的服务项，对应 contract 的 {@code ProviderServiceView}。
 *
 * <p>{@code serviceName} / {@code categoryName} / {@code priceUnit} 与
 * {@code priceMin} / {@code priceMax} 都**来自标准目录**，由 ph-provider 经 ph-catalog 的
 * 接口现取（ADR-0006 禁止 join 别人的表）。所以本表不存名称快照：平台改名后，
 * 服务者页面与 C 端都该立刻跟着变。
 *
 * <p>{@code providerName} 只在**运营的审核队列**里有值（同一模块内取，不算跨模块拼装）；
 * 服务者看自己的列表时为 {@code null}——他不需要看自己的名字，硬填一遍只是多一次查询。
 *
 * <p>{@code status}：0 待审核 / 1 已上架 / 2 已下架 / 3 已驳回。**只有 1 才是对外可见的**，
 * 其余状态即使价格合法也不出现在 C 端（`/api/v1/app/**` 的浏览接口留到下一波）。
 */
public record ProviderServiceView(
        Long id,
        Long providerId,
        String providerName,
        String serviceCode,
        String serviceName,
        String categoryCode,
        String categoryName,
        String price,
        String priceUnit,
        String priceMin,
        String priceMax,
        Integer status,
        String rejectReason,
        LocalDateTime submittedAt,
        LocalDateTime reviewedAt,
        LocalDateTime updatedAt) {
}
