package com.pethealth.account.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 会话与密码策略（ADR-0012）。TTL 是技术参数，改要重启——所以走配置，不进库。
 *
 * <p>默认值：Access 2 小时、Refresh 7 天（交付文档 6.3 定的），与 `application.yml` 里的占位一致。
 */
@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
        String jwtSecret,
        Duration accessTtl,
        Duration refreshTtl,
        int loginMaxFailures,
        Duration loginLockWindow) {

    public AuthProperties {
        accessTtl = accessTtl == null ? Duration.ofHours(2) : accessTtl;
        refreshTtl = refreshTtl == null ? Duration.ofDays(7) : refreshTtl;
        loginMaxFailures = loginMaxFailures <= 0 ? 10 : loginMaxFailures;
        loginLockWindow = loginLockWindow == null ? Duration.ofMinutes(15) : loginLockWindow;
    }
}
