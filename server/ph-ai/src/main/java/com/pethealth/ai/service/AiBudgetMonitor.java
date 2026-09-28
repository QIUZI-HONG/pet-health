package com.pethealth.ai.service;

import com.pethealth.ai.config.AiBudgetProperties;
import com.pethealth.ai.mapper.AiConsultMapper;
import com.pethealth.common.time.AppTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * AI 日预算的**告警**（[ADR-0026](../../../../../docs/adr/0026-ai-degrade-codes-and-daily-budget.md)：
 * 只告警不熔断）。
 *
 * <p>算的是「今天花了多少」：按 {@code ai_consult} 里逐次的 token 用量乘单价。
 * 为什么不用供应商账单：账单按账号月度汇总，回不到「今天」；而这条口径要的正是**当天**。
 *
 * <p>为什么每小时查一次而不是等 23:50：超支发生时越早看到越好，事后算账没有意义。
 * 代价是跨过阈值后会每小时重复告警一次——对「运营看得见」这个目的来说，
 * 重复比漏报好；等 #119 有了真实用量再决定要不要做「每档只报一次」。
 *
 * <p>单价没配（占位为 0）时告警不会响——启动时会有一条提示，别把它当已生效的闸门。
 */
@Component
public class AiBudgetMonitor {

    private static final Logger log = LoggerFactory.getLogger(AiBudgetMonitor.class);

    private static final BigDecimal THOUSAND = new BigDecimal("1000");

    private final AiConsultMapper consultMapper;
    private final AiBudgetProperties budget;

    public AiBudgetMonitor(AiConsultMapper consultMapper, AiBudgetProperties budget) {
        this.consultMapper = consultMapper;
        this.budget = budget;
        if (budget.pricesArePlaceholders()) {
            log.warn("AI 日预算的单价还是占位值（0）：预算告警不会生效。"
                    + "按供应商账单校准 ai.budget.prompt-cny-per-1k / completion-cny-per-1k 后重启。");
        }
    }

    /** 每小时的第 5 分钟检查一次（Asia/Shanghai，与 AppTime 一致）。 */
    @Scheduled(cron = "0 5 * * * *", zone = "Asia/Shanghai")
    public void checkDailyBudget() {
        BigDecimal cost = todayCostCny();
        if (cost.compareTo(budget.dailyCny()) > 0) {
            log.warn("AI 日预算超支：今日估算 {} 元（上限 {} 元）。**只告警不熔断**（ADR-0026）——"
                            + "上线前按真实用量校准 ai.budget.daily-cny，或先看 ai_consult 的用量分布。",
                    cost.toPlainString(), budget.dailyCny().toPlainString());
            return;
        }
        log.info("AI 日预算：今日估算 {} 元（上限 {} 元）",
                cost.toPlainString(), budget.dailyCny().toPlainString());
    }

    /** 今日估算花费（元，两位小数）。分开算输入与输出——两者的单价差很多，混在一起看不出是谁涨的。 */
    public BigDecimal todayCostCny() {
        AiConsultMapper.TokenUsage usage =
                consultMapper.sumUsageSince(AppTime.today().atStartOfDay());
        if (usage == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return estimateCny(usage.promptTokens(), usage.completionTokens(),
                budget.promptCnyPer1k(), budget.completionCnyPer1k());
    }

    /**
     * token 用量 → 估算金额。纯函数，便于单测（不碰库、不碰时钟）。
     *
     * <p>金额用 {@code BigDecimal}：项目约定金额禁浮点（docs/conventions.md）。
     * 先按未舍入的精度算完再取两位，避免「先舍入再相乘」把误差放大。
     */
    static BigDecimal estimateCny(long promptTokens, long completionTokens,
                                 BigDecimal promptCnyPer1k, BigDecimal completionCnyPer1k) {
        BigDecimal prompt = promptCnyPer1k.multiply(BigDecimal.valueOf(promptTokens))
                .divide(THOUSAND, 6, RoundingMode.HALF_UP);
        BigDecimal completion = completionCnyPer1k.multiply(BigDecimal.valueOf(completionTokens))
                .divide(THOUSAND, 6, RoundingMode.HALF_UP);
        return prompt.add(completion).setScale(2, RoundingMode.HALF_UP);
    }
}
