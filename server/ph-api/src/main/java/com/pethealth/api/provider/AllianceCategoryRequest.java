package com.pethealth.api.provider;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新增 / 修改联盟分类维度，对应 contract 的 {@code AllianceCategoryRequest}。
 *
 * <p>同一份请求体用于新增与修改：修改时不改 {@code code}（它是稳定标识，前端与报表引用它），
 * 所以服务层在更新路径上忽略 {@code code} 字段——**不是忘了校验，是刻意不采纳**。
 */
public record AllianceCategoryRequest(

        @NotBlank(message = "维度编码不能为空")
        @Size(max = 32, message = "维度编码最长 32 个字符")
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,31}$",
                message = "维度编码只能用大写字母、数字与下划线，且以字母开头")
        String code,

        @NotBlank(message = "维度名称不能为空")
        @Size(max = 64, message = "维度名称最长 64 个字符")
        String name,

        @Size(max = 255, message = "说明最长 255 个字符")
        String description,

        @Min(value = 0, message = "展示顺序不能为负")
        @Max(value = 9999, message = "展示顺序最大 9999")
        Integer sortOrder) {
}
