package com.pethealth.api.order;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 拒绝用户的取消申请，对应 contract/provider.yaml 的 {@code OrderCancelRejectRequest}。
 *
 * <p>{@code reason} **必填**：ADR-0038 第一节点名要求「已预约阶段用户发起取消需服务者同意，
 * 拒绝要填理由」，理由会展示给用户——他有权知道门店为什么不放行。
 *
 * <p>拒绝之后订单仍是「已预约」：门店要按约履约。
 */
public record OrderCancelRejectRequest(

        @NotBlank(message = "拒绝理由必填")
        @Size(max = 255, message = "拒绝理由最长 255 个字符")
        String reason) {
}
