package com.pethealth.api.app;

/**
 * 回答引用到的一条知识条目（切片 #101）。与 {@code AiConsultView.citations} 配套。
 *
 * <p><b>只承载已复核（vetted）的条目</b>：接口层的形状本身就是那条口径的一部分——
 * 未复核的内容没有出现在这里的通道，它只能以 {@code unvettedUsed} 这个布尔提醒用户
 * 「这次参考了尚未复核的资料」（ADR-0033）。
 *
 * @param entryId       对外编号 {@code K-0010}。跨环境稳定，界面上显示的就是它
 * @param title         条目标题
 * @param sourceTitle   原始资料名（如《WSAVA 2024 犬猫疫苗接种指南》）
 * @param sourceVersion 版本（如「2024 版」）；没有时为空
 * @param sourceUrl     原始资料链接；没有时为空（界面上不显示空链接）
 * @param reviewStatus  复核状态，当前恒为 {@code vetted}
 */
public record AiConsultCitation(
        String entryId,
        String title,
        String category,
        String sourceTitle,
        String sourceVersion,
        String sourceUrl,
        String reviewStatus) {
}
