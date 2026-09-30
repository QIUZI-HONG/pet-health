package com.pethealth.api.provider;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 入驻申请（详情），对应 contract 的 {@code OnboardingApplicationView}。
 *
 * <p>比 {@link OnboardingApplicationSummary} 多三块：门店全貌（{@code provider}）、
 * 资质材料（脱敏）、审核流水（{@code reviewLogs}）。
 *
 * <p>审核流水在这里给全量（含每一次重提）：申请单上的 {@code rejectReason} 只保留最后一次结论，
 * 而「被驳回过几次、每次为什么」才是服务者与运营复盘时要看的东西。
 */
public record OnboardingApplicationView(
        Long id,
        Integer status,
        String rejectReason,
        String reviewRemark,
        Integer submitCount,
        LocalDateTime submittedAt,
        LocalDateTime reviewedAt,
        Long reviewerId,
        Long applicantUserId,
        String applicantName,
        String contactPhone,
        ProviderProfileView provider,
        List<ProviderQualificationView> qualifications,
        List<ReviewLogView> reviewLogs) {
}
