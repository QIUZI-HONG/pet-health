package com.pethealth.api.record;

/**
 * 健康档案的**写侧**接口：服务者报工后把这条服务写成一条档案记录（F004 的「三源并存」）。
 *
 * <p>CONTEXT.md 把档案的来源分三种——用户录入、AI 建议、**服务者报工**。前两种的写路径在
 * ph-record 内部（打卡 / 分项录入 / AI 建议），第三种发生在**订单侧**：报工是订单状态机的一步
 * （ADR-0038 第一节），而 {@code archive_record} 是 ph-record 的表，ph-order 不该碰（ADR-0006）。
 * 于是报工成功后由 ph-order 调这里，实现落在表的拥有方——ADR-0030 第四条点明过这条分工：
 * 「服务者报工写档案的路径属切片 #107，本票只保证它写进来的行能读、能进时间轴、能进报告」。
 *
 * <p><b>为什么它在 ph-api 而不是 ph-record 的 api 包</b>：端口的位置按消费方的数量定
 * （{@code BusinessMessageApi} 的类注释写了同一条判据）——「只有一个模块用」的接口可以留在
 * 拥有方自己的 api 包里，多个消费方用的必须放 ph-api。当前只有 ph-order 调它，
 * 但档案的写入面是**平台级能力**（运营干预、将来的技师端报工、AI 建议入库都可能落到同一个入口），
 * 放进 ph-record 会让每个新消费方都依赖 ph-record 的内部包；而 ph-api 是所有模块都能依赖的
 * 契约模块（与 {@code PetFactsApi} 同处一个包）。
 *
 * <p><b>实现必须由 ph-record 提供，且带 {@code @Primary}</b>：集成测试里可能有等价桩，
 * 两个候选且无主时 Spring 会抛 {@code NoUniqueBeanDefinitionException}——那会把「有实现」
 * 变成 500（{@code ProviderAccessAdapter} 的注释记着这个坑，取舍照它）。
 */
public interface ProviderReportArchiveApi {

    /**
     * 写一条「服务者报工」档案记录（{@code source = 3}）。
     *
     * <p>调用方是报工的**同一个事务**的一部分：订单转「已完成」与这条档案记录必须一起成立
     * ——半成品状态（订单已完成、档案里没有这次服务）正是「三源并存」最没用的那一种，
     * 而且它查不出来（用户看到的订单是对的）。所以实现**不吞异常**：写不进去就让报工一起回滚。
     *
     * <p>实现要自己决定落库的形状（分项、载荷、业务日期），调用方只给事实：
     * 档案的存储口径（ADR-0030）属于拥有表的模块，订单侧不该知道 {@code category} 的取值。
     *
     * @param report 这次服务的事实：哪只宠物、谁的主人、做了什么、门店留下的说明
     */
    void recordServiceReport(ServiceReport report);

    /**
     * 一次到店服务的事实（跨模块用，不是契约 DTO——它不对应任何一个 HTTP 接口）。
     *
     * @param petId       被服务的宠物
     * @param userId      这条记录的主人（下单人 = 宠物主人）：档案行要对得上「谁家的记录」
     * @param serviceName 服务项名（订单下单时的**快照**，如「基础洗护（小型犬）」）；
     *                    目录改名不该改写已发生的服务，所以这里不现取
     * @param remark      门店报工时留下的说明（可空）
     */
    record ServiceReport(long petId, long userId, String serviceName, String remark) {
    }
}
