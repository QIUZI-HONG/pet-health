package com.pethealth.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.content.domain.CommunityQuestion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 提问的数据访问。
 *
 * <p>{@link #adopt} 是「**一条问题只能采纳一个回答**」这条不变量的落点：条件更新把
 * {@code adopted_answer_id IS NULL} 写进 WHERE，受影响行数为 0 就是「已经采纳过了」→ 40900。
 * 先查后判（read → if → write）在并发下会双通过——ADR-0044 记的同类问题，
 * 这里的表现是「一条问题被采纳了两个回答」，而视图里只有一个位置，用户看到的是随机的一个。
 *
 * <p>审核的两个动作与 {@code CommunityCardMapper} 同口径（源状态写进 WHERE、
 * {@code machine_hits} 不因改判而清空）。
 */
@Mapper
public interface CommunityQuestionMapper extends BaseMapper<CommunityQuestion> {

    /**
     * 采纳一个回答。
     *
     * <p>{@code author_id = #{askerId}} 也写进 WHERE：归属校验在 service 层已按 40400 拦过，
     * 这里是第二道（越权在多一层里被挡住，比只靠一处判断安全）。
     *
     * @return 1 = 采纳成功；0 = 已经采纳过（或不是提问者的）→ 调用方报 40900
     */
    @Update("""
            UPDATE community_question
               SET adopted_answer_id = #{answerId},
                   adopted_at = #{now},
                   updated_at = #{now},
                   updated_by = #{askerId},
                   trace_id = #{traceId}
             WHERE id = #{questionId}
               AND author_id = #{askerId}
               AND is_deleted = 0
               AND adopted_answer_id IS NULL
            """)
    int adopt(@Param("questionId") long questionId, @Param("askerId") long askerId,
              @Param("answerId") long answerId, @Param("now") LocalDateTime now,
              @Param("traceId") String traceId);

    /** 回答数 +1（只对**已过审**的回答加，见 {@code QuestionService}）。 */
    @Update("""
            UPDATE community_question
               SET answer_count = answer_count + 1,
                   updated_at = #{now},
                   updated_by = #{operatorId},
                   trace_id = #{traceId}
             WHERE id = #{questionId} AND is_deleted = 0
            """)
    int increaseAnswerCount(@Param("questionId") long questionId, @Param("now") LocalDateTime now,
                            @Param("operatorId") long operatorId, @Param("traceId") String traceId);

    /** 回答数 -1（已发布的回答被下架时）。下界 0：计数是冗余值，不为它牺牲可用性。 */
    @Update("""
            UPDATE community_question
               SET answer_count = GREATEST(answer_count - 1, 0),
                   updated_at = #{now},
                   updated_by = #{operatorId},
                   trace_id = #{traceId}
             WHERE id = #{questionId} AND is_deleted = 0
            """)
    int decreaseAnswerCount(@Param("questionId") long questionId, @Param("now") LocalDateTime now,
                            @Param("operatorId") long operatorId, @Param("traceId") String traceId);

    /** 运营通过（{@code status → 1}）。 */
    @Update("""
            UPDATE community_question
               SET status = 1,
                   reject_reason = NULL,
                   reviewed_at = #{now},
                   reviewed_by = #{operatorId},
                   updated_at = #{now},
                   updated_by = #{operatorId},
                   trace_id = #{traceId}
             WHERE id = #{questionId} AND is_deleted = 0 AND status <> 1
            """)
    int approve(@Param("questionId") long questionId, @Param("now") LocalDateTime now,
                @Param("operatorId") long operatorId, @Param("traceId") String traceId);

    /** 运营驳回 / 下架（{@code status → 2}）。 */
    @Update("""
            UPDATE community_question
               SET status = 2,
                   reject_reason = #{reason},
                   reviewed_at = #{now},
                   reviewed_by = #{operatorId},
                   updated_at = #{now},
                   updated_by = #{operatorId},
                   trace_id = #{traceId}
             WHERE id = #{questionId} AND is_deleted = 0 AND status <> 2
            """)
    int reject(@Param("questionId") long questionId, @Param("reason") String reason,
               @Param("now") LocalDateTime now, @Param("operatorId") long operatorId,
               @Param("traceId") String traceId);
}
