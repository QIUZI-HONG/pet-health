package com.pethealth.api.app;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求，对应 contract/app.yaml 的 {@code RegisterRequest}。
 *
 * <p>后端 DTO 手写（ADR-0005），改契约要同步改这里——两边对不上时接口层测试会红。
 */
public record RegisterRequest(

        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        String phone,

        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 32, message = "密码长度需为 8–32 位")
        @Pattern(regexp = ".*[A-Za-z].*", message = "密码需至少包含一个字母")
        @Pattern(regexp = ".*\\d.*", message = "密码需至少包含一个数字")
        String password,

        @Size(max = 64, message = "昵称最长 64 个字符")
        String nickname) {
}
