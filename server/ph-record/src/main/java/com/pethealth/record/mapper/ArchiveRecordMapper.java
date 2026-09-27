package com.pethealth.record.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.record.domain.ArchiveRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * 档案记录（打卡）的数据访问。
 *
 * <p>普通查询走 MyBatis-Plus（逻辑删除自动过滤）；下面两条是手写 SQL，
 * **必须自己带 {@code is_deleted = 0}**——逻辑删除插件不管手写 SQL（ADR-0011）。
 */
@Mapper
public interface ArchiveRecordMapper extends BaseMapper<ArchiveRecord> {

    /** 某天某个分项是否已有记录（幂等判断用）。 */
    @Select("""
            SELECT COUNT(1) FROM archive_record
             WHERE pet_id = #{petId} AND record_date = #{date} AND category = #{category}
               AND is_deleted = 0
            """)
    int countByDayAndCategory(@Param("petId") long petId,
                             @Param("date") LocalDate date,
                             @Param("category") int category);

    /**
     * 窗口内「日期 × 分项」粒度的聚合。
     *
     * <p>为什么不是直接按分项聚合：一个维度覆盖多个分项（生理 = 体重 + 饮食 + 排泄），
     * 完整度要算「该维有记录的**天数**」——那是各分项日期的**并集**，不是各自天数的最大值。
     * 按分项聚合回来只能取 max，会把「同一天只记了排泄」的日子漏掉（踩过）。
     */
    @Select("""
            SELECT record_date  AS recordDate,
                   category     AS category,
                   SUM(abnormal) AS abnormalCount
              FROM archive_record
             WHERE pet_id = #{petId}
               AND record_date BETWEEN #{from} AND #{to}
               AND is_deleted = 0
             GROUP BY record_date, category
            """)
    List<DayCategoryAggregate> selectDayCategoryAggregates(@Param("petId") long petId,
                                                          @Param("from") LocalDate from,
                                                          @Param("to") LocalDate to);

    /** 窗口内「有记录的日子」，用于连续天数与完整度。 */
    @Select("""
            SELECT DISTINCT record_date FROM archive_record
             WHERE pet_id = #{petId} AND record_date <= #{to} AND is_deleted = 0
             ORDER BY record_date DESC
            """)
    List<LocalDate> selectRecordDatesDesc(@Param("petId") long petId, @Param("to") LocalDate to);

    /** MyBatis 把聚合结果映射到这个 record 上（字段名与 SQL 别名一致）。 */
    record DayCategoryAggregate(LocalDate recordDate, int category, int abnormalCount) {
    }
}
