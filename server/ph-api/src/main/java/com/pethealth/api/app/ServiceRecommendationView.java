package com.pethealth.api.app;

import java.util.List;

/**
 * F011 的推荐结果，对应 contract/app.yaml 的 {@code ServiceRecommendationView}。
 *
 * <p>{@code riskLevel} 是命中症状里最高的就医紧迫程度（1 绿 / 2 黄 / 3 红，与 AI 咨询同一口径），
 * 一个症状都没认出来时为 {@code null}——**它不改变推荐结果**，只影响界面上的提示级别。
 *
 * <p>{@code matched} 为空是**正常结果**（用户说了句无关的话），不是错误；而 {@code degraded=true}
 * 表示 AI 侧这次没读出症状（知识层不可用）——两者在界面上要说不同的话：一个是「换个说法试试」，
 * 另一个是「稍后再试」。
 *
 * <p>{@code notice} 是必须展示的那句话（免责声明；红色时含「立即就医」，认不出时给下一步）。
 * **不许前端自己拼**：安全相关的文案只有一个来源。
 */
public record ServiceRecommendationView(
        Integer riskLevel,
        List<ServiceRecommendationMatch> matched,
        List<ServiceRecommendationItem> recommendations,
        String notice,
        boolean degraded) {
}
