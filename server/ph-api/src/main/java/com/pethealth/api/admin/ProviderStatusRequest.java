package com.pethealth.api.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 变更服务者经营状态（冻结 / 恢复），对应 contract 的 {@code ProviderStatusRequest}。
 *
 * <p>只开放 1（正常）与 3（冻结）两个值：**「清退」不在本切片**——交付文档 2.4 要求清退时
 * 「保留历史订单与档案、已预约订单自动退款」，那要与订单模块（#77）协同，本切片不越界实现
 * （见 ADR-0035 的待澄清）。冻结的语义是「禁止新接单与新上架」，已发生的订单照常履约。
 */
public record ProviderStatusRequest(

        @NotNull(message = "状态不能为空")
        @Min(value = 1, message = "状态只能是 1（正常）或 3（冻结）")
        @Max(value = 3, message = "状态只能是 1（正常）或 3（冻结）")
        Integer status,

        @Size(max = 255, message = "处置原因最长 255 个字符")
        String reason) {
}
