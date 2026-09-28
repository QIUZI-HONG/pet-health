package com.pethealth.api.app;

import java.time.LocalDate;

/**
 * 一份合规文档，对应 contract/app.yaml 的 {@code ComplianceDocumentView}。
 *
 * @param isPlaceholder **正文还是占位**（ADR-0025）：为 true 时前端必须显示「待法务定稿」提示，
 *                      不能让用户以为这是生效条款。这条比文档本身重要——把占位文字当条款展示
 *                      是实实在在的合规风险。
 */
public record ComplianceDocumentView(
        String code,
        String title,
        String body,
        String version,
        LocalDate effectiveFrom,
        boolean isPlaceholder) {
}
