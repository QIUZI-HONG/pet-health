package com.pethealth.api.provider;

import java.util.List;
import java.util.Optional;

/**
 * 服务者的**服务项与营业时间**只读查询（跨模块）：下单链路的前置数据。
 *
 * <p>为什么需要它：下单要写三件与门店有关的事——确认「这个服务项是本店的、已上架」、
 * 拿到它的定价（订单总额）与目录编码（券的适用范围 + 价格区间校验）、
 * 以及根据营业时间校验预约时段。这些都在 {@code provider_service} / {@code provider} 两张表里，
 * 而 ADR-0006 禁止别的模块 join 它（{@code ProviderAccessApi} 只给了身份与门店名，
 * 不够下单用）。
 *
 * <p><b>实现由 ph-provider 提供（本次交付未接线，见 ADR-0048 的「需要协调」）</b>：
 * 两个方法都能直接转给已有的 {@code ServiceListingService} 与 {@code ProviderProfileService}
 * （它们已经在做同一件事：服务项视图是按 {@code provider_service} 查出来再按 code 批量取目录，
 * 营业时间本来就在 {@code provider.business_hours} 里），不需要新写查询逻辑。
 * 在它落地之前，用到本接口的下单接口会以**明确的错误**拒绝，而不是静默按「没有这项服务」处理——
 * 后者会让服务者以为自己的服务项丢了，那是一类查不出来的错。
 *
 * <p><b>为什么返回契约里已有的形状、而不是新造两个内部 record</b>：契约已冻结（ADR-0047），
 * 而 {@code ContractDtoDrift} 把 ph-api 里每个 record 都当成「对外承诺」——
 * 新造的内部 record 会被判成「DTO 有、契约无」。复用 {@link ProviderServiceView} 与
 * {@link BusinessHour} 让这两个方法落在契约已声明的形状上，契约没变、漂移基线也没多一行。
 */
public interface ProviderServiceQueryApi {

    /**
     * 按 id 取**本店已上架**的服务项（含定价与目录侧的名称 / 计量单位 / 价格区间）。
     *
     * @param providerId 服务者 id（资源归属校验的一部分：不是本店的服务项按不存在处理）
     * @param serviceId  {@code provider_service.id}，不是目录项编码
     * @return 已上架（{@code status=1}）时的服务项摘要；不存在、不属于该门店、或还没上架时为空——
     *         三种情况都按「不存在」处理（40400），免得用 id 探测别家门店的服务
     */
    Optional<ProviderServiceView> findListedService(long providerId, long serviceId);

    /**
     * 门店的营业时段（按星期几给出，只含营业的那几天）。
     *
     * <p>给两处用：号源查询要按营业时间铺出时段网格；下单要校验预约时段落在网格上
     * （不在营业时间内 → 40001，交付文档 2.4 的「禁止选择非营业时段」）。
     *
     * @param providerId 服务者 id
     * @return 营业时段；**没有配置营业时间时返回空集合**（调用方按「当天不可预约」处理——
     *         而不是按「全天可约」，后者会让一个还没填营业时间的门店被约爆）
     */
    List<BusinessHour> businessHoursOf(long providerId);
}
