package com.pethealth.catalog.api;

import java.math.BigDecimal;

/**
 * 目录模块对外暴露的**写入**接口：目录外服务提案审核通过后，由 ph-provider 调它建正式项目。
 *
 * <p>为什么建项目这件事必须由目录模块自己做：编码分配（下一个序号）、分类校验、
 * 「编码不可复用」这几条规则都归目录所有，调用方（ph-provider 的审核动作）不该知道
 * 目录项是怎么排号的——它只负责说「这次提案通过了，最终区间是这两个数」。
 *
 * <p>交付文档 F012 / 2.5：目录外服务须经平台审核。服务者只能提案，正式项目一律由平台建。
 */
public interface CatalogItemApi {

    /**
     * 建一个正式目录项（{@code source=2}：来自服务者提案）。
     *
     * @param draft 提案通过后的最终值
     * @return 建好的项目摘要（含分配到的编码），调用方存回提案单的 {@code item_code}
     * @throws com.pethealth.common.error.BusinessException
     *         <ul>
     *           <li><b>40001</b>：分类不存在或已停用、区间非法（下限 &gt; 上限）；
     *           <li><b>40900</b>：指定了编码但该编码已被占用（编码不可复用，只能报错，不能自动改号）。
     *         </ul>
     */
    CatalogQueryApi.ItemInfo createFromProposal(Draft draft);

    /**
     * 提案通过后的最终值。
     *
     * @param categoryCode  归入的分类编码（必须存在且启用）
     * @param name          项目名称（运营可改服务者的建议名）
     * @param description   服务内容说明
     * @param priceMin      最终区间下限，已由 {@link Price} 解析为两位小数
     * @param priceMax      最终区间上限
     * @param priceUnit     计价单位（次 / 只 / 天 / 课时 / 件）
     * @param requestedCode 运营指定的编码；传 {@code null} 表示按分类前缀自动排下一个序号
     */
    record Draft(
            String categoryCode,
            String name,
            String description,
            BigDecimal priceMin,
            BigDecimal priceMax,
            String priceUnit,
            String requestedCode) {
    }
}
