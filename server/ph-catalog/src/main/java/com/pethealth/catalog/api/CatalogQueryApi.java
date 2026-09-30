package com.pethealth.catalog.api;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * 目录模块对外暴露的目录查询接口。
 *
 * <p>存在的意义是让别的模块**不必碰 `service_item` 表**（ADR-0006：禁止 join 对方的表）。
 * 目前唯一的调用方是 ph-provider：服务者服务列表要把「目录项名称 / 分类 / 计价单位 / 区间」拼进视图，
 * 而这些都是目录侧的数据。
 *
 * <p>实现类在 {@code com.pethealth.catalog.service}，由 Spring 注入。
 *
 * <p><b>为什么不存名称快照。</b>快照的第一天就与目录不一致：平台把「基础体检」改名成
 * 「基础体检（含血常规）」后，服务者页面与 C 端应该同时变。代价是每个列表多一次批量查询
 * （按 code 一次取回，不是 N 次），换来的是「目录只有一个真相」。
 */
public interface CatalogQueryApi {

    /**
     * 目录项摘要（跨模块用，不是契约 DTO）。
     *
     * <p>{@code status} 一并给出：服务者列表要标出「目录项已停用」，而停用项不允许新增选品。
     *
     * <p>{@code durationMinutes} 是预计耗时（C 端服务者详情页要展示「约 45 分钟」）；目录项没填时为
     * {@code null}——**不是 0**：「没填」与「零耗时」对用户是两件事。
     */
    record ItemInfo(
            String code,
            String name,
            String categoryCode,
            String categoryName,
            String priceUnit,
            PriceRange priceRange,
            Integer applicablePets,
            Integer status,
            Integer durationMinutes) {
    }

    /**
     * 按编码批量取目录项。
     *
     * @param codes 目录项编码集合；为空集合时不会查库，直接返回空 Map
     * @return 以编码为键的摘要；**查不到的编码不会出现在 Map 里**（调用方按「目录项已不存在」处理，
     *         不要假设每个入参都有值——软删除与停用都可能让一条老引用失去定义）
     */
    Map<String, ItemInfo> findItems(Collection<String> codes);

    /** 单个目录项的摘要（含停用的项）。不存在（或被软删）返回 {@link Optional#empty()}。 */
    Optional<ItemInfo> findItem(String code);

    /**
     * 分类编码 → 分类名（只含**存在且启用**的分类）。
     *
     * <p>给两处用：ph-provider 拼「提案归入哪个分类」时校验分类是否存在（提一个不存在的分类
     * 只会在审核时被退回），以及列表里显示分类名。查不到的编码不会出现在 Map 里——
     * 与 {@link #findItems} 同一口径：**调用方不要假设每个入参都有值**。
     */
    Map<String, String> categoryNames(Collection<String> categoryCodes);
}
