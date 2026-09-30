package com.pethealth.api.catalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新建 / 修改目录分类，对应 contract 的 {@code ServiceCategoryRequest}。
 *
 * <p>同一份请求体用于创建与更新：分类只有六个业务字段，为了「更新时不传就不改」再造一个
 * 部分更新语义的 DTO，收益抵不过两处字段要同步维护的成本。更新时**整体覆盖**。
 *
 * <p>{@code itemCodePrefix} 是该项目编码的前两位（HE / GR / TR / BD / SP / IN）：
 * 项目编码一旦发布不可改（ADR-0034），所以前缀与分类绑定——建分类时一次给对，
 * 之后更新时传别的值会被拒绝（不是静默忽略）。
 */
public record ServiceCategoryRequest(

        @NotBlank(message = "分类编码不能为空")
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,31}$", message = "分类编码用大写字母开头，2–32 位（如 HOSPITAL）")
        String code,

        @NotBlank(message = "编码前缀不能为空")
        @Pattern(regexp = "^[A-Z]{2}$", message = "编码前缀是两位大写字母（如 HE / GR / SP）")
        String itemCodePrefix,

        @NotBlank(message = "分类名称不能为空")
        @Size(max = 64, message = "分类名称最长 64 个字符")
        String name,

        @Size(max = 64, message = "图标标识最长 64 个字符")
        String icon,

        @Size(max = 255, message = "分类说明最长 255 个字符")
        String description,

        Integer sortOrder) {
}
