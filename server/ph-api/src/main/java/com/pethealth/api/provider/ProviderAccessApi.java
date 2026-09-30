package com.pethealth.api.provider;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * 服务者的**只读目录**：谁绑定了哪家店（服务者后台的身份）、以及一批服务者的名字。
 *
 * <p>为什么需要它：{@code provider} / {@code provider_user} 是 ph-provider 的表，
 * ADR-0006 禁止别的模块碰（唯一的例外是 ADR-0009 那条，不适用这里）。ph-provider
 * 自己有一个 {@code ProviderAccess} 组件做这件事，但它在 {@code service} 包里——
 * **跨模块只能走 api 包**（本仓既有形态：{@code CatalogQueryApi}、{@code AiPetApi}…），
 * 而 ph-provider 目前没有对应的 api 接口。
 *
 * <p><b>实现由 ph-provider 提供（本次交付未接线，见 ADR-0044 / ADR-0046 的「需要协调」）</b>：
 * 在 ph-provider 里加一个类实现本接口、把三个方法都转给已有的 {@code ProviderAccess}
 * 与 {@code ProviderMapper} 即可，不需要新写查询逻辑。在它落地之前，
 * 用到本接口的服务者后台接口会以**明确的错误**拒绝（而不是静默按「没有绑定」处理——
 * 后者会让服务者以为自己的门店不存在，那是一类查不出来的错）。
 *
 * <p><b>为什么接口放在 ph-api 而不是某个消费方模块</b>：服务者身份不只券池需要
 * （订单侧核销、考核、报表都要），放进某一个消费方会让下一个消费方依赖它。
 * ph-api 是所有模块都依赖的契约模块，与 {@code AiConsultStatsApi} 是同一处位置调整。
 */
public interface ProviderAccessApi {

    /**
     * 这个登录账号绑定的是哪家服务者。
     *
     * @return 绑定的服务者 id；**没有启用中的绑定时返回空**（还没入驻 / 审核中 / 已驳回 /
     *         绑定被停用）。调用方按「这个账号还没有门店」处理（服务者侧一律 40400）。
     */
    Optional<Long> findProviderId(long userId);

    /**
     * 这个账号在该服务者下是不是**管理员**。
     *
     * <p>券的贡献是「选品定价」同级的管理动作：ADR-0037 的权限矩阵里只给了服务者管理员
     * （技师那一列是 ✗）。技师能核销、能报工，但不能替门店承诺额度。
     */
    boolean isAdmin(long userId);

    /**
     * 批量取服务者名字（运营侧列表要显示「谁承诺的」，光有 id 运营没法用）。
     *
     * @param providerIds 服务者 id 集合；空集合不查库
     * @return id → 名称；**查不到的 id 不会出现在 Map 里**（调用方不要假设每个入参都有值）
     */
    Map<Long, String> providerNames(Collection<Long> providerIds);
}
