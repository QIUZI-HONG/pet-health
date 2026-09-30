package com.pethealth.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.content.domain.CommunityCardLike;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 点赞的数据访问。
 *
 * <p>三个动作刻意都写成**带状态条件的 SQL**，因为「靠受影响行数判断发生了什么」这件事
 * 只在条件写在 WHERE 里时才可靠：
 *
 * <ul>
 *   <li>MySQL Connector/J 默认带 {@code CLIENT_FOUND_ROWS}，{@code INSERT ... ON DUPLICATE KEY UPDATE}
 *       在「值没变」时也会报 1——想靠它区分「新点赞」与「早就点过」是错的（实测踩过）；
 *   <li>把 {@code is_deleted} 写进 WHERE，返回 0 就明确是「条件不成立」（本来就没点过 /
 *       本来就没取消过），1 就是「这一次真的翻了状态」。
 * </ul>
 */
@Mapper
public interface CommunityCardLikeMapper extends BaseMapper<CommunityCardLike> {

    /** 新增一条点赞；并发下撞唯一键会抛 {@code DuplicateKeyException}，由调用方当「已经点过」处理。 */
    @Insert("""
            INSERT INTO community_card_like
              (card_id, user_id, created_at, updated_at, created_by, updated_by, trace_id, is_deleted)
            VALUES (#{cardId}, #{userId}, #{now}, #{now}, #{userId}, #{userId}, #{traceId}, 0)
            """)
    int insertLike(@Param("cardId") long cardId, @Param("userId") long userId, @Param("now") LocalDateTime now,
                   @Param("traceId") String traceId);

    /**
     * 把一条**曾经取消过**的点赞翻回来（{@code is_deleted 1 → 0}）。
     *
     * <p>为什么是「翻回来」而不是「再插一行」：唯一键 {@code (card_id, user_id)} 只允许一行，
     * 再插会撞键。顺带保住了最初点赞的时间戳。
     *
     * @return 1 = 这次真的翻回来了（计数要 +1）；0 = 本来就没点过，或现在已经点着
     */
    @Update("""
            UPDATE community_card_like
               SET is_deleted = 0,
                   updated_at = #{now},
                   updated_by = #{userId},
                   trace_id = #{traceId}
             WHERE card_id = #{cardId} AND user_id = #{userId} AND is_deleted = 1
            """)
    int restoreLike(@Param("cardId") long cardId, @Param("userId") long userId, @Param("now") LocalDateTime now,
                    @Param("traceId") String traceId);

    /**
     * 取消点赞（软删除，docs/conventions.md 的默认口径）。
     *
     * @return 1 = 这次真的取消了（计数要 -1）；0 = 本来就没点着——**幂等**，不是错误
     */
    @Update("""
            UPDATE community_card_like
               SET is_deleted = 1,
                   updated_at = #{now},
                   updated_by = #{userId},
                   trace_id = #{traceId}
             WHERE card_id = #{cardId} AND user_id = #{userId} AND is_deleted = 0
            """)
    int removeLike(@Param("cardId") long cardId, @Param("userId") long userId, @Param("now") LocalDateTime now,
                   @Param("traceId") String traceId);
}
