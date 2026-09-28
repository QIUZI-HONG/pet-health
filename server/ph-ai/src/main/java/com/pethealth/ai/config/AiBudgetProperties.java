package com.pethealth.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * AI 日预算（[ADR-0026](../../../../../docs/adr/0026-ai-degrade-codes-and-daily-budget.md)）。
 *
 * <p>**只告警，不熔断**（ADR-0025 定的口径）：超预算只记 WARN 与一条运营日志，不切断模型——
 * 在没有替代通道（知识检索层）之前，熔断等于把咨询降级成固定话术。
 *
 * <p>单价是**占位值**，必须按供应商的真实账单校准（#119 的埋点出来后一起做）。
 * 分开给输入价与输出价：推理型模型的输出 token 单价通常是输入的数倍，
 * 一个混合单价会把「提示词变长」和「回答变长」这两件不同的事算成一件事。
 *
 * @param dailyCny            当日花费上限（元）；超了只告警
 * @param promptCnyPer1k      输入 1000 token 的单价（元）——**占位值，待校准**
 * @param completionCnyPer1k  输出 1000 token 的单价（元）——**占位值，待校准**
 */
@ConfigurationProperties(prefix = "ai.budget")
public record AiBudgetProperties(BigDecimal dailyCny, BigDecimal promptCnyPer1k,
                                 BigDecimal completionCnyPer1k) {

    private static final BigDecimal DEFAULT_DAILY = new BigDecimal("50.00");

    public AiBudgetProperties {
        dailyCny = dailyCny == null ? DEFAULT_DAILY : dailyCny;
        // 没填单价时按 0 算：宁可估成 0（告警不响）也不要瞎编一个价格进日志
        promptCnyPer1k = promptCnyPer1k == null ? BigDecimal.ZERO : promptCnyPer1k;
        completionCnyPer1k = completionCnyPer1k == null ? BigDecimal.ZERO : completionCnyPer1k;
    }

    /**
     * 有没有单价还没配——启动时据此提醒「这个告警其实不会响」。
     *
     * <p>判据是**任一个为 0**（而不是两个都为 0）：只配了输入价、输出价留空时，
     * 输出那部分会静默算成 0，比完全没配更容易被当成「已经在算了」（评审提出）。
     */
    public boolean pricesArePlaceholders() {
        return promptCnyPer1k.signum() == 0 || completionCnyPer1k.signum() == 0;
    }
}
