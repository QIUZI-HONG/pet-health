package com.pethealth.api.app;

/**
 * 一个被认出来的症状，对应 contract/app.yaml 的 {@code ServiceRecommendationMatch}。
 *
 * <p>{@code title} / {@code riskLevel} 可能为空：词表命中而分诊条目读不到时，症状照样报出来——
 * 「你说的是腹泻」这件事成立，只是拿不到条目里的就医紧迫程度。**空值不要显示成「风险正常」**。
 *
 * <p>{@code entryCode} 是可追溯的条目编号（如 K-0011），与 AI 咨询的引用同一套编号。
 */
public record ServiceRecommendationMatch(
        String symptom,
        String entryCode,
        String title,
        Integer riskLevel) {
}
