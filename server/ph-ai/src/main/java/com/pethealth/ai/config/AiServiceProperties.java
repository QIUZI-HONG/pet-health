package com.pethealth.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 服务的技术参数（ADR-0010 的第一层：技术参数走配置，业务可调项入库）。
 *
 * @param baseUrl   AI 服务地址
 * @param token     内部鉴权令牌，必须与 {@code ai/.env} 的 {@code INTERNAL_TOKEN} 一致
 * @param timeoutMs 读超时。**没有短默认值**：ADR-0017 实测模型偶发 20 秒，
 *                  配 8 秒等于常态化降级
 */
@ConfigurationProperties(prefix = "ai.service")
public record AiServiceProperties(String baseUrl, String token, long timeoutMs) {

    public AiServiceProperties {
        baseUrl = (baseUrl == null || baseUrl.isBlank()) ? "http://127.0.0.1:8000" : baseUrl;
        timeoutMs = timeoutMs <= 0 ? 20_000 : timeoutMs;
    }
}
