package com.pethealth.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.ai.domain.AiConsult;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/** {@code ai_consult} 的读写。 */
@Mapper
public interface AiConsultMapper extends BaseMapper<AiConsult> {

    /**
     * 某个时间点之后消耗的 token 总量（日预算告警用）。
     *
     * <p>手写 SQL 要自己带 {@code is_deleted = 0}——逻辑删除插件不管手写 SQL（ADR-0011）。
     * 走 {@code idx_created_at}（V7 建的），聚合的是一天量级的数据。
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
}
