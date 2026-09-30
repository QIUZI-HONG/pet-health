package com.pethealth.api.catalog;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 启用 / 停用目录项，对应 contract 的 {@code ServiceItemStatusRequest}。
 *
 * <p>停用（{@code status=0}）**只挡住新的选品**，不会自动下架服务者已上架的服务项——
 * 自动下架会打断已预约的订单，而本项目没有推送通道去通知用户。取舍写在 ADR-0034。
 */
public record ServiceItemStatusRequest(

        @NotNull(message = "状态不能为空")
        @Min(value = 0, message = "状态只能是 0（停用）或 1（启用）")
        @Max(value = 1, message = "状态只能是 0（停用）或 1（启用）")
        Integer status) {
}
