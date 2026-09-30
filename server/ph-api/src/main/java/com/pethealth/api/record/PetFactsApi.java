package com.pethealth.api.record;

import java.util.Collection;
import java.util.Map;

/**
 * 宠物档案的**最小事实**查询（跨模块）：订单在创建时把这两项**快照**下来。
 *
 * <p>为什么是快照而不是每次现取：订单是一份历史凭证。宠物改名（或它的物种登记被纠正）之后，
 * 历史订单上写的仍应是当时那笔交易的对象——这与目录项名称「现取不存快照」是相反的口径，
 * 理由也相反（ADR-0034 决定 9 说的是**服务目录**：目录改了，服务者页面与 C 端要同时变；
 * 而订单是已经发生的事）。快照的另一个好处是列表与详情都不再需要跨模块调用：
 * 一页 20 条订单不会变成 40 次宠物查询。
 *
 * <p>为什么不复用 {@code PetView}：下单只需要昵称与物种两个标量。
 * 让档案模块为一次下单去拼整个宠物档案视图（含健康评分、专项照护等）是让无关的字段成为耦合面；
 * 两个窄方法也避免了在 ph-api 里新造 record——契约已冻结，新 record 会被
 * {@code ContractDtoDrift} 判成「DTO 有、契约无」（见
 * {@link com.pethealth.api.provider.ProviderServiceQueryApi} 的说明）。
 *
 * <p><b>实现由 ph-record 提供（本次交付未接线，见 ADR-0048 的「需要协调」）</b>：
 * 两个方法都能直接转给已有的 {@code PetMapper} 批量查，不需要新写查询逻辑。
 * 归属校验不走这里——它走既有的 {@code PetQueryApi.existsOwnedBy}
 * （「宠物不属于我 → 40400」那条规则）。
 */
public interface PetFactsApi {

    /**
     * 批量取宠物昵称。
     *
     * @param petIds 宠物 id 集合；空集合不查库
     * @return id → 昵称；**查不到的 id 不会出现在 Map 里**（已删的宠物查不到，
     *         调用方按空字符串处理，不要让一次历史订单的展示失败）
     */
    Map<Long, String> petNames(Collection<Long> petIds);

    /**
     * 批量取宠物物种码（1 犬 / 2 猫，与 {@code PetView.species} 同码）。
     *
     * <p>服务者侧的订单列表要显示它：门店按物种准备工位与耗材
     * （contract/provider.yaml 的 {@code pet_species}）。
     *
     * @return id → 物种码；同样不保证每个入参都有值
     */
    Map<Long, Integer> petSpecies(Collection<Long> petIds);
}
