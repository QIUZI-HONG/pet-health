package com.pethealth.api.app;

import jakarta.validation.constraints.NotBlank;

/** 退出登录请求，对应 contract/app.yaml 的 {@code LogoutRequest}。 */
public record LogoutRequest(@NotBlank(message = "refresh_token 不能为空") String refreshToken) {
}
