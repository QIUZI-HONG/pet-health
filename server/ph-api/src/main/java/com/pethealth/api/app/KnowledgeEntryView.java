package com.pethealth.api.app;

/**
 * 一条知识条目（C 端），对应 contract/app.yaml 的 {@code KnowledgeEntryView}。
 *
 * <p><b>{@code reviewStatus} 必须展示</b>：平台的知识条目多数是 {@code pending_review}
 * （工程按公开兽医共识起草、待合作兽医复核，ADR-0025 第二节）——把待复核说成已复核是
 * ADR-0033 明令禁止的。所以它是一个必填字段，不是「有就显示」的装饰。
 *
 * <p>{@code body} 只在**详情**里有值（列表为空串）：列表给标题与摘要，点进去才拿正文。
 *
 * <p>{@code riskLevel} 只在症状分诊 / 急救类条目上出现（1 绿 / 2 黄 / 3 红），
 * **不是诊断**，只表示就医紧迫程度（CONTEXT.md 的 RiskLevel）。
 */
public record KnowledgeEntryView(
        String code,
        String title,
        String summary,
        String body,
        String categoryCode,
        String categoryName,
        Integer riskLevel,
        String reviewStatus,
        String sourceTitle,
        String sourceUrl) {
}
