package com.pethealth.privilege.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.privilege.domain.PointEarn;
import com.pethealth.privilege.domain.PointRecord;
import com.pethealth.privilege.domain.PointStat;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * 积分流水的数据访问（全部聚合口径都在这张 SQL 里）。
 *
 * <p>四个口径各一条，全是**业务日**（{@code business_date}，东八区）而不是 {@code created_at}：
 * 跨零点时两者会差一天，而「每日上限」「每日 1 次」都是业务日的概念。
 */
@Mapper
public interface PointRecordMapper extends BaseMapper<PointRecord> {

    /**
     * 今日（业务日）已获得的、**占每日上限**的积分合计。
     *
     * <p>{@code counts_toward_daily_cap = 1} 是关键：邀请（20 分）与完善档案（10 分）
     * 不占上限（ADR-0038 第四节），否则一次邀请就能把当天的日上限吃光，
     * 之后用户做什么都不再得分。
     */
    @Select("""
            SELECT COALESCE(SUM(change_amount), 0)
              FROM point_record
             WHERE is_deleted = 0
               AND user_id = #{userId}
               AND counts_toward_daily_cap = 1
               AND business_date = #{businessDate}
               AND change_amount > 0
            """)
    int sumDailyCapPoints(@Param("userId") long userId, @Param("businessDate") LocalDate businessDate);

    /** 某行为在某个业务日的记录条数（每日次数上限的判据）。 */
    @Select("""
            SELECT COUNT(*)
              FROM point_record
             WHERE is_deleted = 0
               AND user_id = #{userId}
               AND behavior_code = #{behaviorCode}
               AND business_date = #{businessDate}
            """)
    int countOnDay(@Param("userId") long userId, @Param("behaviorCode") String behaviorCode,
                   @Param("businessDate") LocalDate businessDate);

    /** 某行为的**历史总条数**（一次性项与「同一引用只发一次」的判定用）。 */
    @Select("""
            SELECT COUNT(*)
              FROM point_record
             WHERE is_deleted = 0
               AND user_id = #{userId}
               AND behavior_code = #{behaviorCode}
            """)
    int countAll(@Param("userId") long userId, @Param("behaviorCode") String behaviorCode);

    /** 同一行为 + 同一来源引用是否已经发过（幂等判定的读侧；写侧靠唯一键兜底）。 */
    @Select("""
            SELECT COUNT(*)
              FROM point_record
             WHERE is_deleted = 0
               AND user_id = #{userId}
               AND behavior_code = #{behaviorCode}
               AND source_ref = #{sourceRef}
            """)
    int countBySourceRef(@Param("userId") long userId, @Param("behaviorCode") String behaviorCode,
                         @Param("sourceRef") String sourceRef);

    /** 某行为在一个时间窗内的记录条数（每月上限、任务进度都用它）。 */
    @Select("""
            SELECT COUNT(*)
              FROM point_record
             WHERE is_deleted = 0
               AND user_id = #{userId}
               AND behavior_code = #{behaviorCode}
               AND business_date >= #{from}
               AND business_date <= #{to}
            """)
    int countBetween(@Param("userId") long userId, @Param("behaviorCode") String behaviorCode,
                     @Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * 该用户累计获得过多少分（**只算正的**）——月度阶梯的「上月累计」用它。
     */
    @Select("""
            SELECT COALESCE(SUM(change_amount), 0)
              FROM point_record
             WHERE is_deleted = 0
               AND user_id = #{userId}
               AND change_amount > 0
               AND business_date >= #{from}
               AND business_date <= #{to}
            """)
    int sumEarnedBetween(@Param("userId") long userId, @Param("from") LocalDate from,
                         @Param("to") LocalDate to);

    /**
     * 在给定时间窗内**有过行为**的用户 id（邀请结算判「24 小时内有行为」的输入）。
     *
     * <p>把「有行为」落成「在积分流水里有记录」是一个刻意的收口：打卡、签到这些行为本来就会
     * 发分或记一条流水（{@code change = 0} 的也记），所以积分流水就是一份现成的行为账。
     * 代价是「只看不点」的浏览行为不算——这个口径写在 ADR-0046 里，
     * 要扩的话得先有一个跨模块的行为信号。
     *
     * <p>{@code excludeBehavior} 是给邀请结算用的：**完成建档本身不算「有行为」**——
     * 它是有效邀请的触发条件，拿它当活跃证据等于让这条反作弊规则自动失效
     * （任何人都能点一下建档）。要排除它的时候传 {@code PROFILE_COMPLETE}。
     *
     * @return 有一条即返回其 user_id，没有返回 {@code null}
     */
    @Select("""
            SELECT user_id
              FROM point_record
             WHERE is_deleted = 0
               AND user_id = #{userId}
               AND created_at >= #{from}
               AND created_at <= #{to}
               AND (#{excludeBehavior} IS NULL OR behavior_code <> #{excludeBehavior})
             LIMIT 1
            """)
    Long findAnyActivity(@Param("userId") long userId,
                         @Param("from") java.time.LocalDateTime from,
                         @Param("to") java.time.LocalDateTime to,
                         @Param("excludeBehavior") String excludeBehavior);

    /**
     * 一个时间窗内累计获得过分的人与各自的总分（月度阶梯批算的输入）。
     *
     * <p>只取正变动：兑换消耗不该把累计拉下来——阶梯看的是「这个月攒了多少」，
     * 而不是月底还剩多少（否则用分越多越吃亏，而用分恰是平台鼓励的行为）。
     */
    @Select("""
            SELECT user_id AS user_id, COALESCE(SUM(change_amount), 0) AS total
              FROM point_record
             WHERE is_deleted = 0
               AND change_amount > 0
               AND business_date >= #{from}
               AND business_date <= #{to}
             GROUP BY user_id
             ORDER BY user_id
            """)
    List<PointEarn> listEarners(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 积分总览：账户与今日的两组数（一条 SQL 取回，避免 N 次聚合）。 */
    @Select("""
            SELECT
              (SELECT COUNT(*)                          FROM user_point WHERE is_deleted = 0) AS accounts,
              (SELECT COALESCE(SUM(balance), 0)         FROM user_point WHERE is_deleted = 0) AS balance_total,
              (SELECT COALESCE(SUM(total_earned), 0)    FROM user_point WHERE is_deleted = 0) AS earned_total,
              (SELECT COALESCE(SUM(total_spent), 0)     FROM user_point WHERE is_deleted = 0) AS spent_total,
              (SELECT COALESCE(SUM(change_amount), 0)          FROM point_record
                WHERE is_deleted = 0 AND business_date = #{businessDate} AND change_amount > 0)     AS today_earned,
              (SELECT COUNT(DISTINCT user_id)           FROM point_record
                WHERE is_deleted = 0 AND business_date = #{businessDate} AND change_amount > 0)     AS today_users
            """)
    PointStat overview(@Param("businessDate") LocalDate businessDate);

    /**
     * 按行为码聚合的流水条数（C 端任务进度用：每日任务看今天、每周任务看本周）。
     *
     * <p>进度**按流水聚合**而不是在任务表上存计数：任务表存进度马上就有两个真相
     * （ADR-0046 第七节）。{@code change_amount = 0} 的「只记行为不发分」也算一次，
     * 所以这里数的是条数而不是分值。
     */
    @Select("""
            SELECT behavior_code AS behaviorCode, COUNT(*) AS total
              FROM point_record
             WHERE is_deleted = 0
               AND user_id = #{userId}
               AND business_date >= #{from}
               AND business_date <= #{to}
             GROUP BY behavior_code
            """)
    List<BehaviorCount> countByBehavior(@Param("userId") long userId, @Param("from") java.time.LocalDate from,
                                        @Param("to") java.time.LocalDate to);

    /** 行为码 + 该周期内的次数（内联形状，不是接口 DTO）。 */
    record BehaviorCount(String behaviorCode, int total) {
    }
}
