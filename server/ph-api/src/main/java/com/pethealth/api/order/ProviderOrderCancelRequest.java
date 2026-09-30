package com.pethealth.api.order;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 门店取消订单，对应 contract/provider.yaml 的 {@code ProviderOrderCancelRequest}。
 *
 * <p>它和用户侧的 {@link OrderCancelRequest} 是**两个不同的对象**，不是同一份东西的两处写法：
 * 用户取消（尤其「待接单」阶段的自由取消）不该被要求写理由，而**门店单方取消对用户是实打实的伤害**
 * （可能白跑一趟），所以理由必填、并进审计与考核的过程分（ADR-0049 §七）。
 * 之前两份契约共用一个类名 `OrderCancelRequest`，正是「一边必填、一边可选」这种矛盾的来源。
 *
 * <p>{@code reason} 不填 → **40001**（DTO 的 {@code @NotBlank} 在入口就拦，服务层再兜一道）。
 */
public record ProviderOrderCancelRequest(

        @NotBlank(message = "门店取消订单必须填理由（会展示给用户）")
        @Size(max = 255, message = "取消理由最长 255 个字符")
        String reason) {
}
