package com.pethealth.api.app;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 注册归因，对应 contract/app.yaml 的 {@code InviteAttributionRequest}。
 *
 * <p><b>为什么归因是注册之后的一次独立调用，而不是注册请求体的字段</b>（ADR-0039 第一节 /
 * ADR-0046 第一节）：注册请求体（{@link RegisterRequest}）只有手机号 / 口令 / 昵称，
 * 不带邀请码——链接上的 {@code ?invite=CODE} 只是预填，用户填的码在注册成功后由前端
 * 随这一次调用提交。契约已经把两个接口的形状定死，这里不替它改主意。
 *
 * <p>{@code ip} 与手机号段**不在请求体里**：它们由服务端自己取（来源 IP 读连接、
 * 号段读本账号的手机号）——客户端自己声明的地址不算证据（ADR-0046 第三节的判据要用它）。
 */
public record InviteAttributionRequest(

        @NotBlank(message = "邀请码不能为空")
        String inviteCode,

        @NotNull(message = "渠道不能为空")
        Integer channel,

        @Size(max = 128, message = "设备标识最长 128 个字符")
        String deviceId) {
}
