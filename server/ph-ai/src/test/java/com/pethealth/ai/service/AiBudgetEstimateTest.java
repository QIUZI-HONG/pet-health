package com.pethealth.ai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 日预算的估算算术（ADR-0026）。
 *
 * <p>为什么单独测这一小段：它是纯函数，而**告警的准确性全押在它身上**——
 * 算错了要么永远不响（等于没做），要么天天误报（等于关掉）。
 * 真金白银相关的算术不该只被「顺手覆盖一下」。
 */
@DisplayName("AI 日预算估算")
class AiBudgetEstimateTest {

    private static final BigDecimal PROMPT = new BigDecimal("0.001");   // 元 / 1000 token
    private static final BigDecimal COMPLETION = new BigDecimal("0.002");

    @Test
    @DisplayName("输入与输出分开计费，再相加")
    void sumsPromptAndCompletionSeparately() {
        // 1000 输入 = 0.001 元；2000 输出 = 0.004 元 → 合计 0.01 元（两位小数）
        BigDecimal cost = AiBudgetMonitor.estimateCny(1000, 2000, PROMPT, COMPLETION);

        assertThat(cost).isEqualByComparingTo("0.01");
    }

    @Test
    @DisplayName("零用量是 0.00，不是 null 也不是负数")
    void zeroUsageIsZero() {
        assertThat(AiBudgetMonitor.estimateCny(0, 0, PROMPT, COMPLETION)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("单价没配（占位 0）时估成 0：告警不会响，但不会编出一个假价格")
    void placeholderPricesEstimateZero() {
        BigDecimal cost = AiBudgetMonitor.estimateCny(1_000_000, 1_000_000,
                BigDecimal.ZERO, BigDecimal.ZERO);

        assertThat(cost).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("小数位先留足再取两位：不因提前舍入把误差放大")
    void roundsOnlyAtTheEnd() {
        // 三条 333 token 的输入，单价 0.001/1000：每条 0.000333 元。
        // 若逐条先舍入成 0.00，总量会变成 0；正确做法是合起来算再取两位
        BigDecimal total = AiBudgetMonitor.estimateCny(999, 0, PROMPT, COMPLETION);

        assertThat(total).isEqualByComparingTo("0.00");   // 0.000999 → 0.00（不足一分钱）
        BigDecimal bigger = AiBudgetMonitor.estimateCny(9_990, 0, PROMPT, COMPLETION);
        assertThat(bigger).isEqualByComparingTo("0.01");  // 0.00999 → 0.01
    }
}
