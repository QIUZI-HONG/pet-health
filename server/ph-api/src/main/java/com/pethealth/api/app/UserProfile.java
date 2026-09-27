package com.pethealth.api.app;

import java.time.LocalDateTime;

/**
 * 用户资料，对应 contract/app.yaml 的 {@code UserProfile}。
 *
 * <p>{@code phone} 是**脱敏值**（138****8000）：手机号在库里是密文（ADR-0013），
 * 明文只在确实需要的场景（发短信、数据导出）于服务端内部使用，不出接口。
 */
public record UserProfile(
        Long id,
        String phone,
        String nickname,
        String avatar,
        Integer gender,
        Long activePetId,
        LocalDateTime createdAt) {
}
