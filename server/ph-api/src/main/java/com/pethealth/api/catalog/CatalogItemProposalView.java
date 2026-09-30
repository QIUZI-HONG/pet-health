package com.pethealth.api.catalog;

import com.pethealth.api.provider.ReviewLogView;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 目录外服务提案（详情），对应 contract 的 {@code CatalogItemProposalView}。
 *
 * <p>{@code reviewLogs} 是审核流水（含每一次驳回重提，如果有）——提案单上只留最后一次结论，
 * 而「为什么被退」往往要看前几次。流水结构复用服务者侧的 {@link ReviewLogView}：
 * 三类审核（入驻 / 上架 / 提案）共用一张流水表，DTO 也就只有一种形态。
 */
public record CatalogItemProposalView(
        Long id,
        Long providerId,
        String providerName,
        String categoryCode,
        String categoryName,
        String name,
        String description,
        String suggestedPriceMin,
        String suggestedPriceMax,
        String suggestedPriceUnit,
        Integer status,
        String rejectReason,
        String itemCode,
        String reviewRemark,
        LocalDateTime submittedAt,
        LocalDateTime reviewedAt,
        Long reviewerId,
        List<ReviewLogView> reviewLogs) {
}
