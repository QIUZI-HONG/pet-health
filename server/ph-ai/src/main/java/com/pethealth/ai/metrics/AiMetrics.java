package com.pethealth.ai.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * AI 咨询自身的指标（ADR-0029 的「指标与告警清单」）。
 *
 * <p>为什么这两个值得先做：**分级质量出问题时，界面上什么异常都看不到**——
 * 提示词改坏、模型静默换版、上游抖动，用户拿到的仍是一段格式正常的回答。
 * 能最早反映这些的两个信号是「降级占比」与「红黄绿的分布是否突然变了」。
 * 交付文档 B8 要的「抽检与分级漂移告警」落在这两个计数器上。
 *
 * <p>标签基数很小（outcome 3 种、level 3 种），不用担心 series 爆炸。
 * 指标名与 ADR-0029 的告警清单对齐；完整清单与阈值仍属 #88 那张决策票。
 */
@Component
public class AiMetrics {

    /** 每次咨询的结局。降级率 = outcome=degraded 的占比。 */
    public static final String CONSULT = "ph.ai.consult";

    /** 每次咨询的分级。**分布**突然变化是提示词/模型出问题的最早信号。 */
    public static final String RISK_LEVEL = "ph.ai.consult.risk";

    /** 正常走完模型。 */
    public static final String OUTCOME_OK = "ok";
    /** 降级（模型不可用 / 输出不可用 / 读不到图）：用户拿到的是保守建议。 */
    public static final String OUTCOME_DEGRADED = "degraded";
    /** 硬红线短路：**没有调模型**，结论由规则给出（ADR-0021）。 */
    public static final String OUTCOME_RED_FLAG = "red_flag";

    private final MeterRegistry registry;

    public AiMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 记一次咨询的结果。
     *
     * @param degraded  这一轮是否降级
     * @param redFlag   是否由硬红线规则直接判定（此时没有模型参与）
     * @param riskLevel 1 绿 / 2 黄 / 3 红
     */
    public void consulted(boolean degraded, boolean redFlag, int riskLevel) {
        String outcome = redFlag ? OUTCOME_RED_FLAG : degraded ? OUTCOME_DEGRADED : OUTCOME_OK;
        Counter.builder(CONSULT).tag("outcome", outcome).register(registry).increment();
        Counter.builder(RISK_LEVEL).tag("level", Integer.toString(riskLevel)).register(registry).increment();
    }
}
