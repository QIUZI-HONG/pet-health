package com.pethealth.privilege.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 增长与券池模块的装配。
 *
 * <p>显式 {@code @EnableConfigurationProperties} 而不是在属性类上打 {@code @Component}：
 * 全仓 8 组配置都是这个形状（见 {@code ph-file} 的 {@code FileConfig}），照着来读代码时不用猜。
 */
@Configuration
@EnableConfigurationProperties(PrivilegeProperties.class)
public class PrivilegeConfig {
}
