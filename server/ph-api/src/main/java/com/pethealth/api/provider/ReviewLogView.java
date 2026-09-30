package com.pethealth.api.provider;

import java.time.LocalDateTime;

/**
 * 审核流水的一行，对应 contract 的 {@code ReviewLogView}。
 *
 * <p>append-only：进了这张表就不再改（ADR-0028 对审计的同一取舍——能删就等于没有）。
 * {@code actorDomain} 区分「谁做的」：{@code provider} 表示服务者提交/重提/上下架，
 * {@code admin} 表示平台审核。运营后台与服务者后台是两个登录域，流水里必须分得清。
 */
public record ReviewLogView(
        Long id,
        Integer targetType,
        Integer action,
        Long actorId,
        String actorDomain,
        String remark,
        LocalDateTime createdAt) {
}
