package com.pethealth.record.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.record.domain.HealthReport;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 健康报告的数据访问（切片 #115）。
 *
 * <p>写入走**一条原子 SQL**（{@code INSERT ... ON DUPLICATE KEY UPDATE}，ADR-0031 决定二）：
 * 定时任务与用户的读请求可以同时进来，而「先查再插」在并发下会撞 {@code uk_pet_type_period}——
 * 撞唯一键会把事务标记成 rollback-only，接住异常也救不回来（打卡路径上实测过一次）。
 *
 * <p>{@code ON DUPLICATE KEY UPDATE id = id} 是**刻意的不覆盖**：同一个周期已经有报告时什么都不做。
 * 这就是 ADR-0031 的「历史周期不回改」——报告记的是当时的口径，补录、改算法都不该让它变。
 */
@Mapper
public interface HealthReportMapper extends BaseMapper<HealthReport> {

    /** 写一份报告；同周期已存在则**原样保留**（历史不回改）。 */
    @Insert("""
            INSERT INTO health_report
                (pet_id, user_id, type, period_start, period_end, grade, total_score, tier, payload,
                 generated_at, created_at, updated_at, created_by, updated_by, trace_id, is_deleted)
            VALUES
                (#{petId}, #{userId}, #{type}, #{periodStart}, #{periodEnd}, #{grade}, #{totalScore},
                 #{tier}, #{payload}, #{now}, #{now}, #{now}, #{operatorId}, #{operatorId}, #{traceId}, 0)
            ON DUPLICATE KEY UPDATE id = id
            """)
    int insertIfAbsent(@Param("petId") long petId,
                       @Param("userId") long userId,
                       @Param("type") int type,
                       @Param("periodStart") LocalDate periodStart,
                       @Param("periodEnd") LocalDate periodEnd,
                       @Param("grade") String grade,
                       @Param("totalScore") Integer totalScore,
                       @Param("tier") int tier,
                       @Param("payload") String payload,
                       @Param("now") LocalDateTime now,
                       @Param("operatorId") long operatorId,
                       @Param("traceId") String traceId);

    /** 该宠物某一类报告的期数（倒序、只取 id）——保留最近 N 期的裁剪用它。 */
    @Select("""
            SELECT id FROM health_report
             WHERE pet_id = #{petId} AND type = #{type} AND is_deleted = 0
             ORDER BY period_start DESC
            """)
    List<Long> selectIdsDesc(@Param("petId") long petId, @Param("type") int type);
}
