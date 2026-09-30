package com.pethealth.api.app;

import java.util.List;

/**
 * 报告正文，对应 contract/app.yaml 的 {@code HealthReportPayload}（切片 #115）。
 *
 * <p>结构固定成四段（本轮拍板）：① 评分与趋势 ② 异常与亮点 ③ 打卡完成度 ④ 建议清单。
 * 前端按段渲染，**段的顺序由后端给**——顺序是产品口径的一部分，不该由每个端自己排。
 *
 * @param notice 口径说明（免责 + 数据来源 + 「建议清单是行动项、不是医学建议」），前端必须展示
 */
public record HealthReportPayload(String headline, List<Section> sections,
                                  HealthReportStats stats, String notice) {

    /** 报告的一段。 */
    public record Section(String code, String name, List<String> lines, boolean requiresPrivilege) {
    }
}
