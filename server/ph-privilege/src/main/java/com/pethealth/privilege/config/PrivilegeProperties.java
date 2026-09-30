package com.pethealth.privilege.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 增长与券池的可调项（ADR-0010 的分层：业务可调项要有配置入口，不写死在代码里）。
 *
 * @param couponExpiringDays 券到期前**几天**提醒用户（默认 3）
 */
@ConfigurationProperties(prefix = "app.privilege")
public record PrivilegeProperties(int couponExpiringDays) {

    public PrivilegeProperties {
        couponExpiringDays = couponExpiringDays <= 0 ? 3 : couponExpiringDays;
    }
}
