package com.pethealth.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.order.domain.ServiceOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单的数据访问。**状态机的全部落点都在这里**（ADR-0038 第一节 / ADR-0044 的并发教训）。
 *
 * <p>为什么每个迁移都是一条手写的 {@code UPDATE ... WHERE status = ?}：
 *
 * <ul>
 *   <li><b>原子</b>：把源状态写进 WHERE，受影响行数为 0 就是「已经不是那个状态了」。
 *       先查后判（read → if → write）在两个并发请求下会双双通过——ADR-0044 记的
 *       「REPEATABLE READ 下超发」是同一类问题，核销的并发双击也属于它；
 *   <li><b>归属一起判</b>：WHERE 里带上 {@code user_id} / {@code provider_id}，
 *       别的账号的订单根本改不到（越权在 service 层已按 40400 拦掉，这里是第二道）；
 *   <li><b>留痕不能漏</b>：手写 SQL 不走 {@code AuditMetaObjectHandler}（它只作用于
 *       框架生成的 SQL），所以 {@code updated_at / updated_by / trace_id} 必须在 SQL 里显式写
 *       ——「所有写操作留 operator_id 与 trace_id」是硬约束。
 * </ul>
 *
 * <p>另外两条约定：
 *
 * <ul>
 *   <li>进入终态（完成 / 取消）时**必须把 {@code redeem_code_active} 置空**：
 *       核销码的唯一键只约束未结束的订单，否则 6 位码位会被历史订单吃光（迁移 V29 的注释）；
 *   <li>取消**不改券**：券的释放是 {@code CouponApi.release} 的事，两者在同一个事务里
 *       由 service 编排（本模块不碰 {@code coupon} 表，ADR-0006）。
 * </ul>
 */
@Mapper
public interface ServiceOrderMapper extends BaseMapper<ServiceOrder> {

    /** 接单：待接单 → 已预约（接单即承诺：此后用户取消需门店同意）。 */
    @Update("""
            UPDATE `order`
               SET `status` = 1,
                   `accepted_at` = #{now},
                   `updated_at` = #{now},
                   `updated_by` = #{operatorId},
                   `trace_id` = #{traceId}
             WHERE `id` = #{orderId}
               AND `provider_id` = #{providerId}
               AND `status` = 0
               AND `is_deleted` = 0
            """)
    int markAccepted(@Param("orderId") long orderId, @Param("providerId") long providerId,
                     @Param("operatorId") long operatorId, @Param("traceId") String traceId,
                     @Param("now") LocalDateTime now);

    /**
     * 核销：已预约 → 履约中。
     *
     * <p>并发双击只有一个能改到行（另一个拿到 40900），这是 ADR-0038 第二节要的
     * 「原子的条件更新」。**核销 ≠ 收款**：这一步只确认履约开始，钱在门店付（ADR-0036）。
     */
    @Update("""
            UPDATE `order`
               SET `status` = 2,
                   `redeemed_at` = #{now},
                   `updated_at` = #{now},
                   `updated_by` = #{operatorId},
                   `trace_id` = #{traceId}
             WHERE `id` = #{orderId}
               AND `provider_id` = #{providerId}
               AND `status` = 1
               AND `is_deleted` = 0
            """)
    int markRedeemed(@Param("orderId") long orderId, @Param("providerId") long providerId,
                     @Param("operatorId") long operatorId, @Param("traceId") String traceId,
                     @Param("now") LocalDateTime now);

    /** 报工：履约中 → 已完成。**三个槽位齐了才允许调到这里**（service 先校验，见 OrderPhotoWallService）。 */
    @Update("""
            UPDATE `order`
               SET `status` = 3,
                   `reported_at` = #{now},
                   `report_remark` = #{remark},
                   `redeem_code_active` = NULL,
                   `updated_at` = #{now},
                   `updated_by` = #{operatorId},
                   `trace_id` = #{traceId}
             WHERE `id` = #{orderId}
               AND `provider_id` = #{providerId}
               AND `status` = 2
               AND `is_deleted` = 0
            """)
    int markReported(@Param("orderId") long orderId, @Param("providerId") long providerId,
                     @Param("remark") String remark, @Param("operatorId") long operatorId,
                     @Param("traceId") String traceId, @Param("now") LocalDateTime now);

    /**
     * 用户发起取消申请：**不改状态**，只把 {@code cancel_request_status} 置 1（待门店处理）。
     *
     * <p>条件里带 {@code status = 1}：只有「已预约」阶段才需要门店同意；「待接单」是自由取消
     * （走 {@link #cancel}），「履约中」不可取消（ADR-0038 第一节）。
     */
    @Update("""
            UPDATE `order`
               SET `cancel_request_status` = 1,
                   `cancel_requested_at` = #{now},
                   `cancel_reason` = #{reason},
                   `cancel_rejected_reason` = NULL,
                   `updated_at` = #{now},
                   `updated_by` = #{operatorId},
                   `trace_id` = #{traceId}
             WHERE `id` = #{orderId}
               AND `user_id` = #{userId}
               AND `status` = 1
               AND (`cancel_request_status` IS NULL OR `cancel_request_status` <> 1)
               AND `is_deleted` = 0
            """)
    int requestCancel(@Param("orderId") long orderId, @Param("userId") long userId,
                      @Param("reason") String reason, @Param("operatorId") long operatorId,
                      @Param("traceId") String traceId, @Param("now") LocalDateTime now);

    /**
     * 同意用户的取消申请：订单 → 已取消（号源与券由 service 释放）。
     *
     * <p>条件里必须同时有 {@code status = 1} 与 {@code cancel_request_status = 1}：
     * 没有待处理的申请就同意 → 受影响 0 行 → 40900（契约写死的口径）。
     */
    @Update("""
            UPDATE `order`
               SET `status` = 4,
                   `cancel_request_status` = 2,
                   `cancelled_at` = #{now},
                   `cancelled_by` = #{cancelledBy},
                   `redeem_code_active` = NULL,
                   `updated_at` = #{now},
                   `updated_by` = #{operatorId},
                   `trace_id` = #{traceId}
             WHERE `id` = #{orderId}
               AND `provider_id` = #{providerId}
               AND `status` = 1
               AND `cancel_request_status` = 1
               AND `is_deleted` = 0
            """)
    int approveCancel(@Param("orderId") long orderId, @Param("providerId") long providerId,
                      @Param("cancelledBy") int cancelledBy, @Param("operatorId") long operatorId,
                      @Param("traceId") String traceId, @Param("now") LocalDateTime now);

    /**
     * 拒绝用户的取消申请：订单**仍是已预约**（门店要按约履约），只把申请置为已拒绝。
     *
     * <p>拒绝理由必填（ADR-0038 第一节点名要求），由 DTO 的 {@code @NotBlank} 先拦一道。
     */
    @Update("""
            UPDATE `order`
               SET `cancel_request_status` = 3,
                   `cancel_rejected_reason` = #{reason},
                   `updated_at` = #{now},
                   `updated_by` = #{operatorId},
                   `trace_id` = #{traceId}
             WHERE `id` = #{orderId}
               AND `provider_id` = #{providerId}
               AND `status` = 1
               AND `cancel_request_status` = 1
               AND `is_deleted` = 0
            """)
    int rejectCancel(@Param("orderId") long orderId, @Param("providerId") long providerId,
                     @Param("reason") String reason, @Param("operatorId") long operatorId,
                     @Param("traceId") String traceId, @Param("now") LocalDateTime now);

    /**
     * 取消订单：待接单 / 已预约 → 已取消。
     *
     * <p>两个入口共用它：用户自由取消（待接单）与门店取消（两个阶段都能取消）。
     * {@code scopeUserId} 非空时按用户归属判，{@code scopeProviderId} 非空时按门店归属判——
     * 两个都传就要求同时成立（本切片不会这么用，但把口径写死在 SQL 里比在 Java 里拼条件更难写错）。
     *
     * <p>条件里带 {@code (cancel_request_status IS NULL OR cancel_request_status <> 1 OR #{cancelledBy} = 2)}：
     * 用户侧**不能**用这个入口绕过「等门店同意」——有待处理申请时他自己的取消走
     * {@link #requestCancel} 或被 40900 拦住；门店侧（{@code cancelledBy=2}）则可以直接取消，
     * 那一单的申请随之按「已同意」收场（service 会把 {@code cancel_request_status} 置 2）。
     */
    @Update("""
            <script>
            UPDATE `order`
               SET `status` = 4,
                   `cancelled_at` = #{now},
                   `cancelled_by` = #{cancelledBy},
                   `cancel_reason` = COALESCE(#{reason}, `cancel_reason`),
                   `cancel_request_status` = CASE WHEN `cancel_request_status` = 1
                                                  THEN #{cancelRequestOutcome}
                                                  ELSE `cancel_request_status` END,
                   `redeem_code_active` = NULL,
                   `updated_at` = #{now},
                   `updated_by` = #{operatorId},
                   `trace_id` = #{traceId}
             WHERE `id` = #{orderId}
               AND `status` IN (0, 1)
               AND (`cancel_request_status` IS NULL OR `cancel_request_status` &lt;&gt; 1
                    OR #{cancelledBy} = 2)
               <if test="scopeUserId != null"> AND `user_id` = #{scopeUserId} </if>
               <if test="scopeProviderId != null"> AND `provider_id` = #{scopeProviderId} </if>
               AND `is_deleted` = 0
            </script>
            """)
    int cancel(@Param("orderId") long orderId,
               @Param("scopeUserId") Long scopeUserId,
               @Param("scopeProviderId") Long scopeProviderId,
               @Param("cancelledBy") int cancelledBy,
               @Param("cancelRequestOutcome") int cancelRequestOutcome,
               @Param("reason") String reason,
               @Param("operatorId") long operatorId,
               @Param("traceId") String traceId,
               @Param("now") LocalDateTime now);

    // ---------------------------------------------------------------- 统计（供考核 F022，走只读接口 OrderStatsApi）

    /**
     * 区间内的订单数（可按状态过滤）。
     *
     * <p>日期口径是**预约日期**：考核看「这段时间里履约了多少单」，用户提前一周下的单
     * 不该算进下单那一天（口径写在 {@code OrderStatsApi} 的类注释里）。
     * <b>不带状态时也带上 is_deleted</b>：手写 SQL 必须自己写这个条件（ADR-0011）。
     */
    @Select("""
            <script>
            SELECT COUNT(*) FROM `order`
             WHERE `provider_id` = #{providerId}
               AND `appointment_date` &gt;= #{from} AND `appointment_date` &lt;= #{to}
               AND `is_deleted` = 0
               <if test="status != null"> AND `status` = #{status} </if>
            </script>
            """)
    long countByProvider(@Param("providerId") long providerId, @Param("from") LocalDate from,
                         @Param("to") LocalDate to, @Param("status") Integer status);

    /** 服务者**单方取消**的单量（{@code cancelled_by = 2}；用户取消与运营干预不算它头上）。 */
    @Select("""
            SELECT COUNT(*) FROM `order`
             WHERE `provider_id` = #{providerId}
               AND `appointment_date` >= #{from} AND `appointment_date` <= #{to}
               AND `status` = 4
               AND `cancelled_by` = 2
               AND `is_deleted` = 0
            """)
    long countCancelledByProvider(@Param("providerId") long providerId, @Param("from") LocalDate from,
                                  @Param("to") LocalDate to);

    /**
     * 接单响应时长（下单 → 接单）平均分钟数；没有可算的单回 NULL。
     *
     * <p>{@code CAST(ROUND(AVG(...)) AS SIGNED)} 而不是直接映射 DECIMAL：让驱动交回一个整数，
     * 免得「平均 12.5 分钟」在 Java 侧变成 12 还是 13 取决于哪一层截断。
     */
    @Select("""
            SELECT CAST(ROUND(AVG(TIMESTAMPDIFF(MINUTE, `created_at`, `accepted_at`))) AS SIGNED)
              FROM `order`
             WHERE `provider_id` = #{providerId}
               AND `appointment_date` >= #{from} AND `appointment_date` <= #{to}
               AND `accepted_at` IS NOT NULL
               AND `is_deleted` = 0
            """)
    Long averageResponseMinutes(@Param("providerId") long providerId, @Param("from") LocalDate from,
                                @Param("to") LocalDate to);

    /**
     * 平均服务时长（**核销 → 报工**）分钟数；没有可算的单回 NULL。
     *
     * <p>两个时刻都是服务端记的（{@code redeemed_at} / {@code reported_at}），
     * 不采信任何客户端时间戳——否则考核会被前端时钟牵着走。
     */
    @Select("""
            SELECT CAST(ROUND(AVG(TIMESTAMPDIFF(MINUTE, `redeemed_at`, `reported_at`))) AS SIGNED)
              FROM `order`
             WHERE `provider_id` = #{providerId}
               AND `appointment_date` >= #{from} AND `appointment_date` <= #{to}
               AND `redeemed_at` IS NOT NULL AND `reported_at` IS NOT NULL
               AND `is_deleted` = 0
            """)
    Long averageServiceMinutes(@Param("providerId") long providerId, @Param("from") LocalDate from,
                               @Param("to") LocalDate to);

    /**
     * 日期区间内**还没到店**的预约（{@code status in (0, 1)} = 待接单 / 已预约）。
     *
     * <p>给预约提醒用（F026）。刻意**不按时间点过滤**（不在 SQL 里把 {@code appointment_date}
     * 与 {@code start_time} 拼成 DATETIME 去比）：提醒窗口是「未来 N 小时内」，钟点比较放 Java 里
     * 更好读，也避免引入 {@code STR_TO_DATE} 这类只有 MySQL 认的函数——迁移到别的库时少一处方言
     * （ADR-0003 说将来可能换）。所以这里只圈**可能落在窗口里的那两天**，由调用方按小时精筛。
     *
     * <p>已核销在服务的（{@code status = 2}）不在其中：人都到店了，再提醒一次是噪音。
     */
    @Select("""
            SELECT *
              FROM `order`
             WHERE is_deleted = 0
               AND status IN (0, 1)
               AND appointment_date >= #{from} AND appointment_date <= #{to}
            ORDER BY appointment_date ASC, start_time ASC, id ASC
            """)
    List<ServiceOrder> findAwaitingVisitBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
