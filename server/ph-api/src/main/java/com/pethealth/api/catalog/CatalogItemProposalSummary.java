package com.pethealth.api.catalog;

import java.time.LocalDateTime;

/**
 * 目录外服务提案（列表行），对应 contract 的 {@code CatalogItemProposalSummary}。
 *
 * <p>服务者侧看自己的提案（进度与驳回原因），运营侧看待办队列。两侧共用同一结构：
 * 审核员要看的正是服务者提交的那些字段，多加一层「审核专用视图」只会两边字段不同步。
 *
 * <p>{@code itemCode} 只有审核通过后才有值——它是这次提案生成的正式目录项编码。
 */
public record CatalogItemProposalSummary(
        Long id,
        Long providerId,
        String providerName,
        String categoryCode,
        String categoryName,
        String name,
        String suggestedPriceMin,
        String suggestedPriceMax,
        String suggestedPriceUnit,
        Integer status,
        String rejectReason,
        String itemCode,
        LocalDateTime submittedAt,
        LocalDateTime reviewedAt) {
}
