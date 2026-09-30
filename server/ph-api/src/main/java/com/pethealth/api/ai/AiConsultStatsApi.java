package com.pethealth.api.ai;

import java.time.LocalDate;

/**
 * AI 咨询的**只读统计**，供别的模块（健康报告 #115）取数。
 *
 * <p>为什么不直接查 {@code ai_consult}：那是 ph-ai 的表，ADR-0006 禁止跨模块碰表，
 * 而「开一个例外」只有 ADR-0009 那一条（AI 服务读 {@code knowledge_*}）。
 * 报告需要的只是两个数，所以给一个只读接口，而不是开第二个例外。
 *
 * <p>**为什么这个接口在 ph-api 而不是 ph-ai**：跨模块接口一般由「提供方模块」持有
 * （{@code AiPetApi} 在 ph-record、{@code FileUrlApi} 在 ph-file），但这里不行——
 * 消费方 ph-record 已经被 ph-ai 依赖（AI 要读宠物快照），接口放 ph-ai 会形成模块环。
 * ph-api 是所有模块都依赖的契约模块，接口放这里则不会成环；实现仍在 ph-ai
 * （{@code AiConsultStatsService}）。这是一处为了不破 ADR-0006 的模块边界而做的位置调整。
 *
 * <p>与 {@code FileUrlApi} 同一形态：**只读、只给已知调用方用**。需要别的东西时，
 * 先在 AI 模块里想清楚归属，别把它扩成通用查询口子。
 */
public interface AiConsultStatsApi {

    /**
     * 一次咨询统计。
     *
     * @param total    窗口内的咨询次数（含红线短路与降级——那也花掉了一次用户动作）
     * @param redCount 其中判为红色的次数（`risk_level = 3`，含红线规则判的红）
     */
    record ConsultStats(int total, int redCount) {
    }

    /**
     * 某只宠物在 {@code [from, to]}（按业务日，Asia/Shanghai）内的咨询统计。
     *
     * <p>`to` 当天算在内：报告按「业务日」而不是「写入时刻」统计，与打卡、评分的口径一致
     * （同一天 23:50 的咨询属于那一天，不是第二天）。
     */
    ConsultStats summaryOf(long petId, LocalDate from, LocalDate to);
}
