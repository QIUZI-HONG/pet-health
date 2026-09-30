package com.pethealth.api.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 审核通过目录外服务提案，对应 contract 的 {@code CatalogItemProposalApproveRequest}。
 *
 * <p>**最终区间必填**，而不是「不传就照抄服务者的建议」：区间是平台规则（F010），
 * 默认采纳申请方的建议等于把规则制定权给了被审对象。运营可以把建议值抄过来，
 * 但那是他的显式动作，不是系统的默认。
 *
 * <p>{@code code} 可选：不传则按分类前缀自动取下一个序号（如 {@code HE-014}）。
 * 传入时必须与分类前缀一致且未被占用——**编码一旦发布不可改**（ADR-0034），
 * 所以「重号」只能报错，不能自动改号。
 */
public record CatalogItemProposalApproveRequest(

        @NotBlank(message = "最终价格区间下限不能为空")
        String priceMin,

        @NotBlank(message = "最终价格区间上限不能为空")
        String priceMax,

        @Pattern(regexp = "^[\\u4e00-\\u9fa5A-Za-z]{1,4}$", message = "计价单位最多 4 个字（次/只/天/课时/件）")
        String priceUnit,

        @Size(max = 128, message = "项目名称最长 128 个字符")
        String name,

        @Pattern(regexp = "^[A-Z]{2}-\\d{3}$", message = "项目编码形如 HE-001：两位大写字母 + 三位数字")
        String code,

        @Size(max = 255, message = "审核备注最长 255 个字符")
        String remark) {
}
