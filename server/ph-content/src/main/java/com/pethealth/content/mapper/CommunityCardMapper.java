package com.pethealth.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.content.domain.CommunityCard;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 经验卡片的数据访问。
 *
 * <p>点赞计数用**自增 / 自减的条件更新**而不是「读出来 +1 写回去」：
 * 后者在两个人同时点赞时会丢掉一个（读到同一个旧值，各自写回 +1）。
 * {@code GREATEST(like_count - 1, 0)} 是取消点赞的下界——计数是冗余值，
 * 宁可少减一次也不能出现负数（真值永远以 {@code community_card_like} 为准）。
 *
 * <p>手写 SQL 不走 {@code AuditMetaObjectHandler}（它只作用于框架生成的 SQL），
 * 所以 {@code updated_at / updated_by / trace_id} 必须在 SQL 里显式写——
 * 「所有写操作留 operator_id 与 trace_id」是硬约束（ADR-0011）。
 */
@Mapper
public interface CommunityCardMapper extends BaseMapper<CommunityCard> {

    /** 点赞数 +1。 */
    @Update("""
            UPDATE community_card
               SET like_count = like_count + 1,
                   updated_at = #{now},
                   updated_by = #{operatorId},
                   trace_id = #{traceId}
             WHERE id = #{cardId} AND is_deleted = 0
            """)
    int increaseLikeCount(@Param("cardId") long cardId, @Param("now") LocalDateTime now,
                          @Param("operatorId") long operatorId, @Param("traceId") String traceId);

    /** 取消点赞时 -1（下界 0：计数是冗余值，不为它牺牲可用性）。 */
    @Update("""
            UPDATE community_card
               SET like_count = GREATEST(like_count - 1, 0),
                   updated_at = #{now},
                   updated_by = #{operatorId},
                   trace_id = #{traceId}
             WHERE id = #{cardId} AND is_deleted = 0
            """)
    int decreaseLikeCount(@Param("cardId") long cardId, @Param("now") LocalDateTime now,
                          @Param("operatorId") long operatorId, @Param("traceId") String traceId);

    /**
     * 运营通过（{@code status → 1}），并把上一次的驳回理由清掉。
     *
     * <p>源状态写进 WHERE：已经是「已发布」的内容再通过一次影响 0 行，调用方据此报 40900
     * （非法迁移，不是参数错误）。{@code machine_hits} **不清**：机审当时命中了什么，
     * 是这次改判的依据，留着才解释得清「为什么这条曾经被拒过」。
     */
    @Update("""
            UPDATE community_card
               SET status = 1,
                   reject_reason = NULL,
                   reviewed_at = #{now},
                   reviewed_by = #{operatorId},
                   updated_at = #{now},
                   updated_by = #{operatorId},
                   trace_id = #{traceId}
             WHERE id = #{cardId} AND is_deleted = 0 AND status <> 1
            """)
    int approve(@Param("cardId") long cardId, @Param("now") LocalDateTime now,
                @Param("operatorId") long operatorId, @Param("traceId") String traceId);

    /** 运营驳回 / 下架（{@code status → 2}）；理由必填，会展示给作者自己。 */
    @Update("""
            UPDATE community_card
               SET status = 2,
                   reject_reason = #{reason},
                   reviewed_at = #{now},
                   reviewed_by = #{operatorId},
                   updated_at = #{now},
                   updated_by = #{operatorId},
                   trace_id = #{traceId}
             WHERE id = #{cardId} AND is_deleted = 0 AND status <> 2
            """)
    int reject(@Param("cardId") long cardId, @Param("reason") String reason, @Param("now") LocalDateTime now,
               @Param("operatorId") long operatorId, @Param("traceId") String traceId);
}
