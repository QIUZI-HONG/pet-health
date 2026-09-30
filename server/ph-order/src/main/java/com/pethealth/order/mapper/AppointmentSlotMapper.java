package com.pethealth.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.order.domain.AppointmentSlot;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 号源的数据访问：占用的**全部并发正确性**就在下面几条 SQL 里（ADR-0038 第一节）。
 *
 * <p>为什么不「先查还剩几个，再决定占不占」：两个并发请求会同时读到「还剩 1」，
 * 于是双双写下占用——超卖。把判据写进 {@code WHERE booked_count < capacity} 之后，
 * 数据库行锁保证只有一个能改到行，另一个受影响 0 行（调用方据此回 40900）。
 * 这是 ADR-0038 说的「用唯一键 / 乐观锁实现，后到者得到 40900」，
 * 也是 ADR-0044 在券额度上踩过之后定下的同一套写法。
 *
 * <p><b>为什么「懒建行」是一条独立的 {@code INSERT IGNORE}，而不是让占用与建行共用一条语句</b>
 * ——两条更「聪明」的写法都试过，都不能用，理由记在这里免得下一个人再试一遍：
 *
 * <ol>
 *   <li><b>「先 UPDATE，没改到再 INSERT，再重试 UPDATE」→ MySQL 的经典死锁</b>：
 *       失败的 INSERT 在唯一键上留下共享锁，随后重试的 UPDATE 要把它升级成排他锁，
 *       多个请求互相等对方的共享锁。实测 4 个并发请求抢同一时段，有一个拿到 50000
 *       （{@code DeadlockLoserDataAccessException}），而 ADR-0038 要的是 40900；
 *   <li><b>一条 {@code INSERT ... ON DUPLICATE KEY UPDATE} → 判不出成败</b>：它对
 *       「已存在的行被写成原值」本该返回 0 受影响行，但 Connector/J 默认带
 *       {@code CLIENT_FOUND_ROWS}（{@code useAffectedRows=false}），于是这一档也返回 1
 *       ——实测 4 个并发请求**全部**拿到「占到」，超卖 4 倍。
 * </ol>
 *
 * <p>现在的分工：{@link #insertIfAbsent} 只把行**建出来**（{@code booked_count = 0}，
 * 不占用），跑在**独立事务**里（见 {@code SlotRowWriter}），它的锁在条件更新之前就已释放；
 * {@link #tryOccupy} 只负责占用，永远是一条带容量条件的更新。建行那张纸若因外层事务回滚
 * 而留了下来，留下的也只是「一个还没被占的时段」，不是幽灵占用。
 */
@Mapper
public interface AppointmentSlotMapper extends BaseMapper<AppointmentSlot> {

    /**
     * 占用一个时段（原子）：容量没满才 {@code +1}。
     *
     * @return 1 表示占到；0 表示「行不存在」或「已满」——调用方先判行在不在，
     *         再决定是懒建一行还是回 40900
     */
    @Update("""
            UPDATE `appointment_slot`
               SET `booked_count` = `booked_count` + 1,
                   `updated_at` = #{now},
                   `updated_by` = #{operatorId},
                   `trace_id` = #{traceId}
             WHERE `provider_id` = #{providerId}
               AND `service_id` = #{serviceId}
               AND `slot_date` = #{slotDate}
               AND `start_time` = #{startTime}
               AND `booked_count` < `capacity`
               AND `is_deleted` = 0
            """)
    int tryOccupy(@Param("providerId") long providerId, @Param("serviceId") long serviceId,
                  @Param("slotDate") LocalDate slotDate, @Param("startTime") String startTime,
                  @Param("operatorId") long operatorId, @Param("traceId") String traceId,
                  @Param("now") LocalDateTime now);

    /**
     * 懒建一个时段行（{@code booked_count = 0}）：已经存在就什么都不做。
     *
     * <p>用 {@code INSERT IGNORE} 而不是普通 INSERT：并发建同一行时，输的那个不该看到异常
     * ——「行已经在了」正是它想要的结论。容量判定不在这里（这里根本不占用）。
     *
     * @return 1 表示这一行是本次建出来的；0 表示已经存在
     */
    // 必须是 @Insert 而不是 @Update：MyBatis 按注解决定 SqlCommandType，而 MyBatis-Plus 的
    // blockAttack 拦截器会按「UPDATE 语句」去解析它，撞上 INSERT 语法直接抛
    // UnsupportedOperationException（实测：写成 @Update 时下单 100% 报 50000）。
    // 「这条 SQL 是什么类型」有两个读者，注解与语句必须说同一件事。
    @Insert("""
            INSERT IGNORE INTO `appointment_slot`
                (`provider_id`, `service_id`, `slot_date`, `start_time`, `end_time`, `capacity`, `booked_count`,
                 `created_by`, `updated_by`, `trace_id`)
            VALUES (#{providerId}, #{serviceId}, #{slotDate}, #{startTime}, #{endTime},
                    #{capacity}, 0, #{operatorId}, #{operatorId}, #{traceId})
            """)
    int insertIfAbsent(@Param("providerId") long providerId, @Param("serviceId") long serviceId,
                       @Param("slotDate") LocalDate slotDate, @Param("startTime") String startTime,
                       @Param("endTime") String endTime, @Param("capacity") int capacity,
                       @Param("operatorId") long operatorId, @Param("traceId") String traceId);

    /** 这一行在不在（非锁定读：用来区分「还没建出来」与「建出来了但已满」）。 */
    @Select("""
            SELECT COUNT(*) FROM `appointment_slot`
             WHERE `provider_id` = #{providerId}
               AND `service_id` = #{serviceId}
               AND `slot_date` = #{slotDate}
               AND `start_time` = #{startTime}
               AND `is_deleted` = 0
            """)
    int countOf(@Param("providerId") long providerId, @Param("serviceId") long serviceId,
                @Param("slotDate") LocalDate slotDate, @Param("startTime") String startTime);

    /**
     * 释放一个时段（原子）：有占用才 {@code -1}，不会减成负数。
     *
     * <p>幂等：只有真正推进了取消的那一个请求会调到它（取消本身是条件更新），
     * 所以这里不需要额外的去重；{@code booked_count > 0} 是防御性的下限。
     */
    @Update("""
            UPDATE `appointment_slot`
               SET `booked_count` = `booked_count` - 1,
                   `updated_at` = #{now},
                   `updated_by` = #{operatorId},
                   `trace_id` = #{traceId}
             WHERE `provider_id` = #{providerId}
               AND `service_id` = #{serviceId}
               AND `slot_date` = #{slotDate}
               AND `start_time` = #{startTime}
               AND `booked_count` > 0
               AND `is_deleted` = 0
            """)
    int release(@Param("providerId") long providerId, @Param("serviceId") long serviceId,
                @Param("slotDate") LocalDate slotDate, @Param("startTime") String startTime,
                @Param("operatorId") long operatorId, @Param("traceId") String traceId,
                @Param("now") LocalDateTime now);
}
