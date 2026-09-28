package com.pethealth.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 服务的技术参数（ADR-0010 的第一层：技术参数走配置，业务可调项入库）。
 *
 * @param baseUrl   AI 服务地址
 * @param token     内部鉴权令牌，必须与 {@code ai/.env} 的 {@code INTERNAL_TOKEN} 一致
 * @param timeoutMs 读超时。**没有短默认值**：ADR-0017 实测模型偶发 20 秒，
 *                  配 8 秒等于常态化降级
 * @param fileBaseUrl **本服务对外可达的地址**（AI 服务用它来取档案照片）。签名读地址是相对路径，
 *                   浏览器按站点解析没问题，但 AI 服务没有「我们的站点」这个概念——
 *                   它要把图片取回来再内联给模型，所以要给绝对地址（测试报告 D7）。
 */
@ConfigurationProperties(prefix = "ai.service")
public record AiServiceProperties(String baseUrl, String token, long timeoutMs, String fileBaseUrl) {

    public AiServiceProperties {
        baseUrl = (baseUrl == null || baseUrl.isBlank()) ? "http://127.0.0.1:8000" : baseUrl;
        timeoutMs = timeoutMs <= 0 ? 20_000 : timeoutMs;
        fileBaseUrl = (fileBaseUrl == null || fileBaseUrl.isBlank()) ? "http://127.0.0.1:8080" : fileBaseUrl;
        // 结尾的斜杠会让拼接出现双斜杠，归一化掉
        fileBaseUrl = fileBaseUrl.endsWith("/") ? fileBaseUrl.substring(0, fileBaseUrl.length() - 1) : fileBaseUrl;
    }
}
