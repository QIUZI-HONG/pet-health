package com.pethealth.api.app;

import jakarta.validation.constraints.NotBlank;

/** 登录请求，对应 contract/app.yaml 的 {@code LoginRequest}。 */
public record LoginRequest(

        @NotBlank(message = "手机号不能为空")
        String phone,

        @NotBlank(message = "密码不能为空")
        String password) {
}
