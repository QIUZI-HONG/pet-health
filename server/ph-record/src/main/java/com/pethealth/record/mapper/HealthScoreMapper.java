package com.pethealth.record.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.record.domain.HealthScore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 健康评分的数据访问。逻辑删除由 MyBatis-Plus 自动过滤。
 *
 * <p>当日行的写入走 {@link #upsertScore} 这一条 SQL：唯一键 {@code uk_pet_calc_date}
 * 保证一天一行，而「先查再插」在并发下会撞键——撞键会把事务标记成 rollback-only，
 * 接住异常也会在提交时炸成 500（2026-09-28 测试报告的并发用例实测过）。
 */
@Mapper
public interface HealthScoreMapper extends BaseMapper<HealthScore> {

    /**
     * 写当日评分行。{@code ON DUPLICATE KEY UPDATE} 让并发的两次重算互为「再算一遍」。
     *
     * <p>并发是真会发生的：双击、双标签页、打卡与防疫同时写，都会触发当天重算。
     * 谁最后写谁赢并不重要（同一只宠物、同一套公式），重要的是**不能报 500**。
     *
     * <p>{@code is_deleted = 0} 也放在 UPDATE 里：万一这行被软删过，重算要能把它救回来。
     */
    @Insert("""
            INSERT INTO health_score
                (pet_id, calc_date, total_score, physiology, behavior, hygiene, epidemic, elderly,
                 included_dimensions, created_at, updated_at, created_by, updated_by, trace_id, is_deleted)
            VALUES
                (#{petId}, #{calcDate}, #{totalScore}, #{physiology}, #{behavior}, #{hygiene},
                 #{epidemic}, #{elderly}, #{includedDimensions}, #{now}, #{now},
                 #{operatorId}, #{operatorId}, #{traceId}, 0)
            ON DUPLICATE KEY UPDATE
                total_score = VALUES(total_score),
                physiology = VALUES(physiology),
                behavior = VALUES(behavior),
                hygiene = VALUES(hygiene),
                epidemic = VALUES(epidemic),
                elderly = VALUES(elderly),
                included_dimensions = VALUES(included_dimensions),
                is_deleted = 0,
                updated_at = VALUES(updated_at),
                updated_by = VALUES(updated_by),
                trace_id = VALUES(trace_id)
            """)
    int upsertScore(@Param("petId") long petId,
                    @Param("calcDate") LocalDate calcDate,
                    @Param("totalScore") int totalScore,
                    @Param("physiology") Integer physiology,
                    @Param("behavior") Integer behavior,
                    @Param("hygiene") Integer hygiene,
                    @Param("epidemic") Integer epidemic,
                    @Param("elderly") Integer elderly,
                    @Param("includedDimensions") int includedDimensions,
                    @Param("now") LocalDateTime now,
                    @Param("operatorId") long operatorId,
                    @Param("traceId") String traceId);
}
