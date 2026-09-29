package com.pethealth.record.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.record.domain.ArchiveRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 档案记录（打卡与防疫）的数据访问。
 *
 * <p>普通查询走 MyBatis-Plus（逻辑删除自动过滤）；手写 SQL
 * **必须自己写 {@code is_deleted = 0}**——逻辑删除插件不管手写 SQL（ADR-0011）。
 *
 * <p>打卡的写入是例外：{@link #upsertCheckIn} 一条 SQL 吃掉「更新 / 复活 / 新增」三种情况。
 * 那里绕开了逻辑删除，因为 {@code uk_checkin_slot} 是物理唯一键，**撤销过的行仍占着那个槽**。
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
     * 写一条打卡分项：**一条 SQL 完成「同槽更新 / 复活 / 新增」**。
     *
     * <p>为什么不用「先查再插/改」：`uk_checkin_slot` 是物理唯一键（槽 = 宠物 + 日期 + 分项），
     * 查与写之间的窗口里，另一个并发请求可以插进同一槽——撞唯一键会**把当前事务标记成
     * rollback-only**，接住异常也没用，提交时照样 500（并发用例实测：4 个并发提交里 2 个 500）。
     * `ON DUPLICATE KEY UPDATE` 从根上避开这个竞态。
     *
     * <p>同时它天然覆盖「撤销后重填」：撤掉的行 {@code is_deleted = 1}，但 {@code checkin_slot}
     * 不含这个字段，冲突时走 UPDATE 分支并把它复活（2026-09-28 测试报告 D2）。
     * 语义是「撤销 = 这条记错了，重填就是改这一条」，所以复活而不是新插一行。
     *
     * <p>{@code backfilled} 用 {@code IF} 而不是直接覆盖：补录标记一旦为真就不再抹掉
     * （事后改成「当场录入」是篡改）。
     */
    @Insert("""
            INSERT INTO archive_record
                (pet_id, user_id, record_date, category, content, abnormal, backfilled, numeric_value,
                 source, created_at, updated_at, created_by, updated_by, trace_id, is_deleted)
            VALUES
                (#{petId}, #{userId}, #{date}, #{category}, #{content}, #{abnormal}, #{backfilled},
                 #{numericValue}, #{source}, #{now}, #{now}, #{operatorId}, #{operatorId}, #{traceId}, 0)
            ON DUPLICATE KEY UPDATE
                content = VALUES(content),
                abnormal = VALUES(abnormal),
                numeric_value = VALUES(numeric_value),
                backfilled = IF(backfilled = 1 OR VALUES(backfilled) = 1, 1, 0),
                is_deleted = 0,
                updated_at = VALUES(updated_at),
                updated_by = VALUES(updated_by),
                trace_id = VALUES(trace_id)
            """)
    int upsertCheckIn(@Param("petId") long petId,
                      @Param("userId") long userId,
                      @Param("date") LocalDate date,
                      @Param("category") int category,
                      @Param("content") String content,
                      @Param("abnormal") int abnormal,
                      @Param("backfilled") int backfilled,
                      @Param("numericValue") BigDecimal numericValue,
                      @Param("source") int source,
                      @Param("now") LocalDateTime now,
                      @Param("operatorId") long operatorId,
                      @Param("traceId") String traceId);

    /** 软删一只宠物的全部档案记录（打卡与防疫），注销链路用。返回受影响行数。 */
    @Update("""
            UPDATE archive_record
               SET is_deleted = 1,
                   updated_at = #{now},
                   updated_by = #{operatorId},
                   trace_id = #{traceId}
             WHERE pet_id = #{petId} AND is_deleted = 0
            """)
    int softDeleteByPet(@Param("petId") long petId,
                        @Param("now") LocalDateTime now,
                        @Param("operatorId") long operatorId,
                        @Param("traceId") String traceId);

    /**
     * 防疫记录的概况：记录条数 + 最早的到期日。一次查询就够（{@code EpidemicSummary}）。
     *
     * <p>**刻意不带时间窗口**：疫苗与驱虫是按年/按月打的，用「近 7 天」筛等于要求用户每次接种
     * 都当天录入（ADR-0025 修的就是这个口子）。
     */
    @Select("""
            SELECT COUNT(*) AS totalCount, MIN(due_on) AS earliestDue
              FROM archive_record
             WHERE pet_id = #{petId} AND category = 7 AND is_deleted = 0
            """)
    EpidemicSummary selectEpidemicSummary(@Param("petId") long petId);

    /** 防疫概况。{@code totalCount = 0} 表示没有任何记录；{@code earliestDue} 可空（记录没填到期日）。 */
    record EpidemicSummary(int totalCount, java.time.LocalDate earliestDue) {
    }

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

    /**
     * 某只宠物到 {@code to} 为止**有记录的全部日期**（倒序、去重）。
     *
     * <p>注意它**没有下界**——返回的是这只宠物的完整历史，不是某个窗口。调用方
     * （{@code CheckInService.streak}）需要完整历史才能算「历史最长连续天数」，
     * 只看 7 天窗口是算不出来的。代价是行数随宠物年龄增长，所以只按日期取（不取整行）。
     *
     * <p>原 Javadoc 写的是「窗口内」，与 SQL 不符——读代码的人会以为有下界（测试报告 D29 同类）。
     *
     * @param petId 宠物 id
     * @param to    上界（含），通常是「今天」
     */
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
