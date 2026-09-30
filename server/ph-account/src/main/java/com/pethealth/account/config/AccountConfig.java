package com.pethealth.account.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 账号模块的装配：配置绑定 + 口令编码器。
 *
 * <p>bcrypt cost 用 10：交付文档只在管理员密码那条写了 bcrypt，C 端口令同样用它是自然的推广（ADR-0012）。
 * cost 每 +1 计算量翻倍，10 是「够慢到不至于被离线爆破、又不至于拖垮登录接口」的常规取值。
 */
@Configuration
@EnableConfigurationProperties({AuthProperties.class, RateLimitProperties.class, IdempotencyProperties.class,
        ConsoleProperties.class})
public class AccountConfig {

    private static final int BCRYPT_COST = 10;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_COST);
    }
}
