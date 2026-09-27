package com.pethealth.api.app;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * 更新用户资料，对应 contract/app.yaml 的 {@code UpdateProfileRequest}。
 *
 * <p>只传要改的字段：字段为 {@code null} 表示不改。
 */
public record UpdateProfileRequest(

        @Size(max = 64, message = "昵称最长 64 个字符")
        String nickname,

        @Size(max = 512, message = "头像地址最长 512 个字符")
        String avatar,

        @Min(value = 0, message = "性别只能是 0/1/2")
        @Max(value = 2, message = "性别只能是 0/1/2")
        Integer gender) {
}
