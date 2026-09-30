package com.pethealth.api.order;

import jakarta.validation.constraints.Size;

/**
 * 取消订单 / 发起取消申请，对应 contract/app.yaml 与 contract/provider.yaml 的
 * {@code OrderCancelRequest}（两份契约各写一遍，后端是同一个 DTO）。
 *
 * <p>{@code reason} **可选**：ADR-0038 只对「门店拒绝用户的取消申请」强制要求理由
 * （那个入参在 {@link OrderCancelRejectRequest}），所以取消本身不强制。
 * 但门店取消时用户至少该看到为什么，界面应当引导填写。
 *
 * <p>取消**不产生任何资金动作**：钱在门店付，没有退款单（ADR-0036）。
 */
public record OrderCancelRequest(

        @Size(max = 255, message = "取消理由最长 255 个字符")
        String reason) {
}
