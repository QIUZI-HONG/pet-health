package com.pethealth.api.admin;

import java.util.List;

/**
 * 运营看板的订单统计（契约 `admin.yaml` 的同名 schema）。
 *
 * <p>**金额是展示口径**：平台不经手资金（ADR-0002 / ADR-0036），`pay_amount` 是
 * 「门店应收多少」的合计，不是「平台收了多少」——契约里也是这么写的，别当成流水。
 *
 * @param period     统计窗口（`yyyy-MM`，回显给页面）
 * @param total      窗口内的订单总数（各状态之和）
 * @param by_status  按状态分组（0 待接单 … 4 已取消），状态名由服务端给（前端不再翻译一遍）
 * @param pay_amount 窗口内「预估实付」合计（两位小数字符串）
 * @param cancel_rate 取消率（两位小数字符串，如 "0.08"）：运营看的是它的趋势
 */
public record AdminOrderStatsView(
        String period,
        long total,
        List<StatusRow> byStatus,
        String payAmount,
        String cancelRate) {

    /** 一个状态的条数与金额。 */
    public record StatusRow(int status, String label, long count, String payAmount) {
    }
}
