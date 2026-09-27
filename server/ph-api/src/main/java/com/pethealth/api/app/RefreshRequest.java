package com.pethealth.api.app;

import jakarta.validation.constraints.NotBlank;

/** 刷新令牌请求，对应 contract/app.yaml 的 {@code RefreshRequest}。 */
public record RefreshRequest(@NotBlank(message = "refresh_token 不能为空") String refreshToken) {
}
