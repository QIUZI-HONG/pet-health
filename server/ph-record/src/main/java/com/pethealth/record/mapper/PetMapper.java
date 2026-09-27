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
}
