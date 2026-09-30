package com.pethealth.api.provider;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 服务者（门店）信息，对应 contract 的 {@code ProviderProfileView}。
 *
 * <p>{@code phone} 是脱敏值（ADR-0013）。{@code status} 是服务者的经营状态
 * （0 待审核 / 1 正常 / 2 驳回 / 3 冻结）——**读接口在非正常态也返回门店信息**，
 * 这样服务者能看见「审核中」而不是收到一个 404；写接口才按状态拒绝。
 *
 * <p>{@code level} / {@code monthlyScore} / {@code rating} 本期不参与任何计算
 * （考核 #58、评价体系未落地），字段先给出，避免将来改表。
 */
public record ProviderProfileView(
        Long id,
        String name,
        Integer type,
        Integer category,
        String logo,
        String intro,
        String address,
        String lng,
        String lat,
        String phone,
        List<BusinessHour> businessHours,
        Integer status,
        Integer level,
        String regionCode,
        String monthlyScore,
        String rating,
        LocalDateTime approvedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
