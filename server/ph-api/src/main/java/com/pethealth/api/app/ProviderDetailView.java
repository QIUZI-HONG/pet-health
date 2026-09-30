package com.pethealth.api.app;

import java.util.List;

import com.pethealth.api.provider.BusinessHour;

/**
 * 服务者详情（C 端「看店」），对应 contract/app.yaml 的 {@code ProviderDetailView}。
 *
 * <p><b>这一条就是「下单页看不到价格」的答案</b>（ADR-0047 推进顺序里欠的那一半）：
 * {@code services} 是这家店**已上架**的服务项连同它当下的定价，用户在下单前就能看到价。
 *
 * <p>三条刻意的取舍（与契约同文）：
 *
 * <ul>
 *   <li>{@code qualifications} 只给**已通过审核且未过期**的材料，且不含证件编号；
 *   <li>{@code services} 只有 {@code status=1}（已上架）：待审核 / 已下架 / 已驳回都不出现——
 *       「在架」是用户唯一会下单的东西，把待审的项列出来等于给他一个点了会 40400 的按钮；
 *   <li>{@code businessHours} 里**不出现的星期几就是休息**（空数组 = 整周休息）：
 *       复用服务者侧同一个 {@link BusinessHour}，两端对「休息」的表达不会分叉。
 * </ul>
 *
 * <p>{@code services} 为空是**正常结果**（这家店此刻没有在架服务），不是错误——
 * 前端照实说「暂无在架服务」。
 *
 * <p>价格是**展示值**，不是收款事实：钱在门店直接付给服务者（ADR-0036）。
 */
public record ProviderDetailView(
        Long id,
        String name,
        Integer type,
        String typeName,
        String logo,
        String intro,
        String address,
        String lng,
        String lat,
        String phone,
        String rating,
        List<BusinessHour> businessHours,
        List<ProviderQualificationSummaryView> qualifications,
        List<ProviderServiceOfferView> services) {
}
