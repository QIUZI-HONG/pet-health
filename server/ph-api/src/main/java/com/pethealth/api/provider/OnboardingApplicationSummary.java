package com.pethealth.api.provider;

import java.time.LocalDateTime;

/**
 * 入驻申请（列表行），对应 contract 的 {@code OnboardingApplicationSummary}。
 *
 * <p>{@code rejectReason} 在列表里就给出来：服务者最关心的是「卡在哪」，
 * 点进详情才能看到原因是多余的一步（用户故事 62）。
 *
 * <p>{@code submitCount} 是驳回重提的次数：审核员据此判断这是不是一份反复被退回的申请。
 */
public record OnboardingApplicationSummary(
        Long id,
        Long providerId,
        String providerName,
        Integer providerType,
        Long applicantUserId,
        String applicantName,
        String contactPhone,
        Integer status,
        String rejectReason,
        Integer submitCount,
        LocalDateTime submittedAt,
        LocalDateTime reviewedAt) {
}
