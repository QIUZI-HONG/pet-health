package com.pethealth.api.catalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 目录外服务提案（服务者提交），对应 contract 的 {@code CatalogItemProposalRequest}。
 *
 * <p>依据交付文档 F012 与 2.5：「目录外服务须经平台审核」。**不放开自由建项**——
 * 服务者只能提案，通过后由平台入库成为正式目录项，之后才能在自己店内定价上架
 * （2026-09-29 项目所有者口径，见 ADR-0034）。
 *
 * <p>{@code priceMin} / {@code priceMax} 是**服务者的建议值，仅供运营参考**：
 * 最终区间由运营在审核通过时给出。让申请方定义平台规则是不成立的——
 * 那样区间校验就成了自己批自己。
 */
public record CatalogItemProposalRequest(

        @NotBlank(message = "所属分类不能为空")
        @Size(max = 32, message = "分类编码最长 32 个字符")
        String categoryCode,

        @NotBlank(message = "项目名称不能为空")
        @Size(max = 128, message = "项目名称最长 128 个字符")
        String name,

        @Size(max = 512, message = "服务内容说明最长 512 个字符")
        String description,

        @NotBlank(message = "建议价格区间下限不能为空")
        String priceMin,

        @NotBlank(message = "建议价格区间上限不能为空")
        String priceMax,

        @Pattern(regexp = "^[\\u4e00-\\u9fa5A-Za-z]{1,4}$", message = "计价单位最多 4 个字（次/只/天/课时/件）")
        String priceUnit) {
}
