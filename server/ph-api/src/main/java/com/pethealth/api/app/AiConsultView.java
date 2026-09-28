package com.pethealth.api.app;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一次咨询的结果，对应 contract/app.yaml 的 {@code AiConsultView}。
 *
 * <p>{@code riskLevel} 是**就医紧迫程度**，不是诊断（CONTEXT.md 的 RiskLevel）：1 绿 / 2 黄 / 3 红。
 * 前端按它决定视觉层级，红色必须带「立即送医」与 24 小时医院入口。
 *
 * @param redFlagHits 命中的硬红线规则编号。**非空表示这次没有经过模型**（ADR-0021），
 *                    前端可以据此把结论写成「命中急症信号」而不是「AI 认为」
 * @param disclaimer  免责声明，由后端给（医疗文案不该散落在前端各处）
 * @param degraded    true 表示这是降级答复（模型超时 / 不可用 / 输出不可用），不是模型结论
 * @param quotaPerDay / remainingToday 免费额度与今日剩余。**本期只提示不拦截**（ADR-0024）：
 *                    到量后接口照常返回结果，界面据此劝用户邀请好友（#112 才有解锁路径）
 */
public record AiConsultView(
        long id,
        int riskLevel,
        List<String> possibleCauses,
        String actionSuggestion,
        boolean needHospital,
        List<String> careTips,
        List<String> redFlagHits,
        boolean degraded,
        String degradeReason,
        String modelVersion,
        String promptVersion,
        int latencyMs,
        String disclaimer,
        LocalDateTime createdAt,
        int quotaPerDay,
        int remainingToday) {
}
