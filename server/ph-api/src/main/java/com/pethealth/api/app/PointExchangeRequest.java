package com.pethealth.api.app;

import jakarta.validation.constraints.NotNull;

/**
 * 用积分兑换券，对应 contract/app.yaml 的 {@code PointExchangeRequest}。
 *
 * <p>{@code optionId} 必填：**兑换没有别的参数**——兑什么由档位定，客户端不能指定券模板
 * （能指定模板就等于让客户端决定平台发多少钱）。
 *
 * <p>幂等只能靠 `Idempotency-Key`（ADR-0028 / ADR-0046 第六节）：一次兑换没有天然的业务引用，
 * 所以这个头在兑换接口上是**必须带**的。
 */
public record PointExchangeRequest(

        @NotNull(message = "兑换档位必填")
        Long optionId) {
}
