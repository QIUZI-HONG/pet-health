package com.pethealth.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 免费额度（交付文档 F006：免费用户每日 3 次）。
 *
 * <p>**本期只计数、不拦截**（[ADR-0024](../../../../../docs/adr/0024-quota-care-community-tracking-packages.md)）：
 * 解锁路径在 #112 权益引擎里，现在拦截会把用户挡在一扇没有出口的门后面。
 * 计数先跑起来是为了拿到真实用量分布——它比任何估算都更能决定 #112 的阶梯怎么定。
 *
 * @param freePerDay 每日免费次数；到量只是提示，不改变接口行为
 */
@ConfigurationProperties(prefix = "ai.quota")
public record AiQuotaProperties(int freePerDay) {

    public AiQuotaProperties {
        freePerDay = freePerDay <= 0 ? 3 : freePerDay;
    }
}
