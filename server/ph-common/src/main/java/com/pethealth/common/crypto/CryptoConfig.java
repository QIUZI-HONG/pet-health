package com.pethealth.common.crypto;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 加密密钥的配置绑定（ADR-0013）。
 *
 * <p>显式 {@code @EnableConfigurationProperties} 而不是在 {@link FieldCryptoProperties} 上打
 * {@code @Component}：配置类就该由配置类注册，混用两种写法以后没人说得清哪个生效。
 */
@Configuration
@EnableConfigurationProperties(FieldCryptoProperties.class)
public class CryptoConfig {
}
