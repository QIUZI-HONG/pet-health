package com.pethealth.api.app;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 一份健康报告，对应 contract/app.yaml 的 {@code HealthReport}（切片 #115，决策见 ADR-0031）。
 *
 * <p><b>只有两种报告</b>（本轮拍板）：周报（上一个完整自然周）与月报（上一个完整自然月）。
 *
 * <p><b>它是派生视图</b>：内容由规则模板从 {@code health_score} 按天的行与 {@code archive_record}
 * 拼出来，**不经过模型**——报告里的每个数字都来自数据，模型在这里只能产生幻觉、不能产生信息。
 * 历史周期不回改：报告记的是**当时**的口径。
 *
 * <p>界面文案统一叫「健康周报 / 健康月报」，**不写「AI 生成」**：我们没调模型，
 * 声称是 AI 生成属于「做了没做的事」（ADR-0028 的口径）。
 */
public record HealthReportView(
        Long id,
        /** 1 周报 / 2 月报 */
        Integer type,
        String typeName,
        LocalDate periodStart,
        LocalDate periodEnd,
        String grade,
        Integer totalScore,
        /** 1 基础版 / 2 完整版；**本期只标注不拦截**（ADR-0031 决定五） */
        Integer tier,
        /** 完整版对应的权益码（与 #80 的权益码表对齐） */
        String privilegeCode,
        String tierNote,
        LocalDateTime generatedAt,
        HealthReportPayload payload) {
}
