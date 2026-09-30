package com.pethealth.api.app;

/**
 * 资质摘要（C 端详情页），对应 contract/app.yaml 的 {@code ProviderQualificationSummaryView}。
 *
 * <p><b>没有证件编号字段——不是「脱敏后为空」，而是根本没有这个字段。</b>不给出口就绕不开：
 * C 端用户没有核验证件号的需求，而每多一个字段就多一处泄漏面。运营审核走的是服务者侧的
 * {@code ProviderQualificationView}（那里给脱敏值，因为审核要用）。
 *
 * <p>{@code validUntil} 为空表示**长期有效**（没有到期日的材料），不是「没填」。
 */
public record ProviderQualificationSummaryView(
        Integer type,
        String typeName,
        String name,
        String validUntil) {
}
