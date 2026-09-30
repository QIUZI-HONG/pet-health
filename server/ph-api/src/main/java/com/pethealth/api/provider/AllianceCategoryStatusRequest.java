package com.pethealth.api.provider;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 启用 / 停用一个联盟分类维度，对应 contract 的 {@code AllianceCategoryStatusRequest}。
 *
 * <p>用 1 / 0 而不是布尔：与库里 {@code enabled} 列同形，与 `provider` / `service_item` 的
 * 状态写法一致（ADR-0011 的「状态值原样进出」）。
 */
public record AllianceCategoryStatusRequest(

        @NotNull(message = "启用状态不能为空")
        @Min(value = 0, message = "启用状态只能是 0（停用）或 1（启用）")
        @Max(value = 1, message = "启用状态只能是 0（停用）或 1（启用）")
        Integer enabled) {
}
