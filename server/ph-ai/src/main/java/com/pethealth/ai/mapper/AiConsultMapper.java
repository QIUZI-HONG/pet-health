package com.pethealth.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.ai.domain.AiConsult;
import com.pethealth.ai.domain.AiUsageRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/** {@code ai_consult} 的读写。 */
@Mapper
public interface AiConsultMapper extends BaseMapper<AiConsult> {

    /**
     * 某个时间点之后消耗的 token 总量（日预算告警用）。
     *
     * <p>手写 SQL 要自己带 {@code is_deleted = 0}——逻辑删除插件不管手写 SQL（ADR-0011）。
     * 走 {@code idx_created_at}（V11 补的：V7 那三条索引前导列是 user_id / pet_id / risk_level，
     * 都用不上这个「按时间范围聚合」的形状，缺了它就是每小时一次全表扫）。
     */
    @Select("""
            SELECT IFNULL(SUM(prompt_tokens), 0)     AS promptTokens,
                   IFNULL(SUM(completion_tokens), 0) AS completionTokens
              FROM ai_consult
             WHERE created_at >= #{from} AND is_deleted = 0
            """)
    TokenUsage sumUsageSince(@Param("from") LocalDateTime from);

    /** 一段时间的 token 用量。字段名与 SQL 别名一致，MyBatis 直接映射。 */
    record TokenUsage(long promptTokens, long completionTokens) {
    }
    /**
     * 按「模型 × 版本」聚合一个时间窗的用量（运营的 AI 成本页用）。
     *
     * <p>只聚合**事实**：调用数、输入 / 输出 token、红线短路数（未经模型的那部分，
     * `red_flag_hits` 非空即为命中）、降级数（{@code degraded = 1}）。
     * **不乘单价**——单价与补贴比例是运营假设，页面参数化即可，不进库也不进 SQL。
     *
     * <p>排序按 token 总量降序：一眼看到「谁在花预算」，同量再按模型名与版本给确定顺序。
     */
    @Select("""
            SELECT model_name                                                      AS modelName,
                   model_version                                                   AS modelVersion,
                   COUNT(*)                                                        AS calls,
                   IFNULL(SUM(prompt_tokens), 0)                                   AS promptTokens,
                   IFNULL(SUM(completion_tokens), 0)                               AS completionTokens,
                   SUM(CASE WHEN red_flag_hits IS NOT NULL THEN 1 ELSE 0 END)      AS redFlagCalls,
                   SUM(CASE WHEN degraded = 1 THEN 1 ELSE 0 END)                   AS degradedCalls
              FROM ai_consult
             WHERE created_at >= #{from} AND created_at < #{to} AND is_deleted = 0
             GROUP BY model_name, model_version
             ORDER BY (IFNULL(SUM(prompt_tokens), 0) + IFNULL(SUM(completion_tokens), 0)) DESC,
                      model_name, model_version
            """)
    List<AiUsageRow> usageByModel(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
