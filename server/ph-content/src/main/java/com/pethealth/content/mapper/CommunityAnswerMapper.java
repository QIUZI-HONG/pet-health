package com.pethealth.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.content.domain.CommunityAnswer;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 回答的数据访问。
 *
 * <p>没有「设为已采纳」这样的方法：采纳的真值在 {@code community_question.adopted_answer_id}
 * 上（本表不存副本，见 {@code CommunityAnswer} 的类注释）。
 *
 * <p>审核的两个动作与其他两种内容同口径（源状态写进 WHERE、{@code machine_hits} 不清空）。
 */
@Mapper
public interface CommunityAnswerMapper extends BaseMapper<CommunityAnswer> {

    /** 运营通过（{@code status → 1}）。 */
    @Update("""
            UPDATE community_answer
               SET status = 1,
                   reject_reason = NULL,
                   reviewed_at = #{now},
                   reviewed_by = #{operatorId},
                   updated_at = #{now},
                   updated_by = #{operatorId},
                   trace_id = #{traceId}
             WHERE id = #{answerId} AND is_deleted = 0 AND status <> 1
            """)
    int approve(@Param("answerId") long answerId, @Param("now") LocalDateTime now,
                @Param("operatorId") long operatorId, @Param("traceId") String traceId);

    /** 运营驳回 / 下架（{@code status → 2}）。 */
    @Update("""
            UPDATE community_answer
               SET status = 2,
                   reject_reason = #{reason},
                   reviewed_at = #{now},
                   reviewed_by = #{operatorId},
                   updated_at = #{now},
                   updated_by = #{operatorId},
                   trace_id = #{traceId}
             WHERE id = #{answerId} AND is_deleted = 0 AND status <> 2
            """)
    int reject(@Param("answerId") long answerId, @Param("reason") String reason,
               @Param("now") LocalDateTime now, @Param("operatorId") long operatorId,
               @Param("traceId") String traceId);
}
