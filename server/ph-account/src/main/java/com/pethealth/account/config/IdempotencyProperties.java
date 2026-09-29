package com.pethealth.account.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 写接口幂等的开关与窗口（ADR-0028）。
 *
 * <p>阈值是技术参数（改要重启），按 ADR-0010 走配置不进库。
 *
 * @param enabled 是否启用。关掉之后带 {@code Idempotency-Key} 的请求也照常重复执行
 * @param ttl     幂等键保留多久。这个值同时是「客户端可以在多久之内安全重试」的承诺：
 *                太短则真实重试落在窗口外仍会重复执行，太长则白占内存
 */
@ConfigurationProperties(prefix = "app.idempotency")
public record IdempotencyProperties(Boolean enabled, Duration ttl) {

    /** 10 分钟：覆盖「网络抖动后重试」「用户手抖双击」「标签页重开再提交」这三种常见跨度。 */
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

    public IdempotencyProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        ttl = ttl == null || ttl.isNegative() || ttl.isZero() ? DEFAULT_TTL : ttl;
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }
}
