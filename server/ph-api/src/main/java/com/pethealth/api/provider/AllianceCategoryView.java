package com.pethealth.api.provider;

import java.time.LocalDateTime;

/**
 * 服务者联盟分类维度（{@code provider_alliance_category}），对应 contract 的
 * {@code AllianceCategoryView}。
 *
 * <p>{@code id} 就是 {@code provider.category} 里存的值——它不是无关紧要的代理键，
 * 而是归属关系的那一半。所以契约里也把它当取值用（服务者归属请求传的就是它）。
 *
 * <p>{@code providerCount} 是**当前归属到这一维度的门店数**（未软删的）。给运营看这一列
 * 是为了让「停用一档」之前能看见会影响谁：停用不移动已有归属，但运营得先知道有多少家挂在上面。
 */
public record AllianceCategoryView(
        Long id,
        String code,
        String name,
        String description,
        Integer sortOrder,
        Integer enabled,
        Long providerCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
