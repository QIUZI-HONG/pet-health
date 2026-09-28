package com.pethealth.ai.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** AI 客户端的装配点。 */
@Configuration
@EnableConfigurationProperties({AiServiceProperties.class, AiQuotaProperties.class,
        AiBudgetProperties.class})
public class AiConfig {
}
