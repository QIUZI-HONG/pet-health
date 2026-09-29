package com.pethealth.record.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.record.domain.Pet;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 宠物表的数据访问。
 *
 * <p>普通查询走 MyBatis-Plus，逻辑删除自动带上 {@code is_deleted = 0}；
 * **下面三个手写 SQL 必须自己写这个条件**——手写 SQL 绕过了逻辑删除插件（ADR-0011），
 * 漏了就会把回收站里的宠物查出来。
 */
@Mapper
public interface PetMapper extends BaseMapper<Pet> {

    /** 软删除：置位 + 记录删除时间（恢复窗口的起点）。返回 0 表示不存在 / 不是他的 / 已经删过了。 */
    @Update("""
            UPDATE pet
               SET is_deleted = 1,
                   deleted_at = #{now},
                   updated_at = #{now},
                   updated_by = #{operatorId},
                   trace_id = #{traceId}
             WHERE id = #{petId}
               AND user_id = #{userId}
               AND is_deleted = 0
            """)
    int softDelete(@Param("petId") long petId,
                   @Param("userId") long userId,
                   @Param("now") LocalDateTime now,
                   @Param("operatorId") long operatorId,
                   @Param("traceId") String traceId);

    /**
     * 恢复：只有**删除时间在窗口内**的才恢复得回来。
     *
     * <p>超过窗口的这里返回 0，调用方按 40400 处理——「过期了」与「不存在」对客户端是同一种结果，
     * 分开报错只会白送一个「这个 id 曾经存在」的信息。
     */
    @Update("""
            UPDATE pet
               SET is_deleted = 0,
                   deleted_at = NULL,
                   updated_at = #{now},
                   updated_by = #{operatorId},
                   trace_id = #{traceId}
             WHERE id = #{petId}
               AND user_id = #{userId}
               AND is_deleted = 1
               AND deleted_at >= #{restoreDeadline}
            """)
    int restore(@Param("petId") long petId,
                @Param("userId") long userId,
                @Param("restoreDeadline") LocalDateTime restoreDeadline,
                @Param("now") LocalDateTime now,
                @Param("operatorId") long operatorId,
                @Param("traceId") String traceId);

    /** 回收站：只列还能恢复的那些，最近删的排前面。 */
    @Select("""
            SELECT *
              FROM pet
             WHERE user_id = #{userId}
               AND is_deleted = 1
               AND deleted_at >= #{restoreDeadline}
             ORDER BY deleted_at DESC, id DESC
            """)
    List<Pet> selectRecycleBin(@Param("userId") long userId,
                               @Param("restoreDeadline") LocalDateTime restoreDeadline);

    /**
     * 至少有一只宠物的用户 id（去重、升序）。
     *
     * <p>给每日提醒批算用：它在固定时刻遍历所有用户，是这条链路上唯一一次全表级的读。
     * **在 SQL 里 DISTINCT**，而不是把整张 {@code pet} 表捞回内存再去重——每只宠物一行，
     * 一个多宠用户就多一行，内存里去重等于把库表规模搬进堆里。
     *
     * <p>已软删的宠物不算「活跃」：手写 SQL 必须自己带上 {@code is_deleted = 0}（见类注释）。
     * 排序是为了让批算顺序可复现，也便于将来按用户号分批跑。
     */
    @Select("""
            SELECT DISTINCT user_id
              FROM pet
             WHERE is_deleted = 0
             ORDER BY user_id
            """)
    List<Long> selectActiveUserIds();
}
