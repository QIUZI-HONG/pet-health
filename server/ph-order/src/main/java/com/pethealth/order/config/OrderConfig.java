package com.pethealth.order.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 订单模块的装配。与其它模块同形（见 {@code ph-file} 的 {@code FileConfig}）。 */
@Configuration
@EnableConfigurationProperties(OrderProperties.class)
public class OrderConfig {
}
