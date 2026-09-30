package com.pethealth.privilege.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.privilege.domain.ContributionCouponStat;
import com.pethealth.privilege.domain.Coupon;
import com.pethealth.privilege.domain.CouponStat;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 券实例的数据访问，含全部**统计口径**的 SQL。
 *
 * <p>为什么口径写在 SQL 里而不是查到内存里算：券会越积越多（已过期的也留着参与对账），
 * 把整表拉进内存算「占用中」在一个真实体量上不成立。四个数各一条聚合，
 * 条件与 {@code CouponQuota} 的公式一一对应，改口径时两处一起改。
 *
 * <p><b>四个条件互斥且覆盖全部行</b>，所以恒有
 * {@code issued = redeemed + reserved + expired}（ADR-0037 第三节的对账口径）。
 * 注意「已核销」排在最前：一张核销过的券即使后来过了 valid_until，也只算已核销。
 */
@Mapper
public interface CouponMapper extends BaseMapper<Coupon> {

    /**
     * 一条贡献的额度账（服务者列表与详情用）。
     *
     * @param now 判定「是否过期 / 是否占用中」的当前时间——传进来而不是用 {@code NOW()}，
     *            这样口径与业务侧读到的时刻一致，也便于测试
     */
    @Select("""
            SELECT
              COUNT(*)                                                                AS issued,
              SUM(CASE WHEN status = 3 THEN 1 ELSE 0 END)                             AS redeemed,
              SUM(CASE WHEN status IN (1, 2) AND valid_until >= #{now} THEN 1 ELSE 0 END) AS reserved,
              SUM(CASE WHEN status = 4 OR valid_until < #{now} THEN 1 ELSE 0 END)     AS expired
              FROM coupon
             WHERE is_deleted = 0
               AND contribution_id = #{contributionId}
            """)
    ContributionCouponStat statOfContribution(@Param("contributionId") long contributionId,
                                              @Param("now") LocalDateTime now);

    /**
     * 一批贡献各自的额度账（列表页一次取回，不是每行一次查询）。
     */
    @Select("""
            <script>
            SELECT
              contribution_id                                                         AS contribution_id,
              COUNT(*)                                                                AS issued,
              SUM(CASE WHEN status = 3 THEN 1 ELSE 0 END)                             AS redeemed,
              SUM(CASE WHEN status IN (1, 2) AND valid_until >= #{now} THEN 1 ELSE 0 END) AS reserved,
              SUM(CASE WHEN status = 4 OR valid_until &lt; #{now} THEN 1 ELSE 0 END)     AS expired
              FROM coupon
             WHERE is_deleted = 0
               AND contribution_id IN
               <foreach collection="contributionIds" item="id" open="(" separator="," close=")">#{id}</foreach>
             GROUP BY contribution_id
            </script>
            """)
    List<ContributionCouponStat> statsOfContributions(@Param("contributionIds") List<Long> contributionIds,
                                                      @Param("now") LocalDateTime now);

    /**
     * 券池按来源分组的总账（运营总览 + 对账）。
     */
    @Select("""
            SELECT
              source                                                                  AS source,
              COUNT(*)                                                                AS issued,
              SUM(CASE WHEN status = 3 THEN 1 ELSE 0 END)                             AS redeemed,
              SUM(CASE WHEN status IN (1, 2) AND valid_until >= #{now} THEN 1 ELSE 0 END) AS reserved,
              SUM(CASE WHEN status = 4 OR valid_until < #{now} THEN 1 ELSE 0 END)     AS expired
              FROM coupon
             WHERE is_deleted = 0
             GROUP BY source
             ORDER BY source
            """)
    List<CouponStat> statsBySource(@Param("now") LocalDateTime now);

    /**
     * 平台补贴券用了多少张（发放上限的判据之一）。
     */
    @Select("""
            SELECT COUNT(*)
              FROM coupon
             WHERE is_deleted = 0
               AND template_id = #{templateId}
            """)
    int countIssuedByTemplate(@Param("templateId") long templateId);

    /**
     * 把已过期的券翻成「已过期」状态（每日批算用），返回处理条数。
     *
     * <p>只翻**未核销**的（{@code status in (1,2)}）：已核销的券即使过了期也还是已核销，
     * 改它会破坏对账口径。条件更新（{@code WHERE} 里带状态与时间）意味着批算本身是幂等的，
     * 重复跑不会重复计数。
     *
     * <p>注意额度**不靠这次翻状态释放**：可发放额度按「未过期未核销」实时算
     * （见 {@code CouponQuota}），所以即使批算没跑，过期券也不会继续占着额度。
     * 翻状态的作用是让状态与事实一致、并把这次释放记进额度流水。
     */
    @Update("""
            UPDATE coupon
               SET status = 4,
                   updated_at = #{now}
             WHERE is_deleted = 0
               AND status IN (1, 2)
               AND valid_until < #{now}
            """)
    int expireUnused(@Param("now") LocalDateTime now);

    /** 已过期券所属的贡献 id 列表（同一贡献只记一条释放流水，所以要 distinct）。 */
    @Select("""
            SELECT DISTINCT contribution_id
              FROM coupon
             WHERE is_deleted = 0
               AND contribution_id IS NOT NULL
               AND status IN (1, 2)
               AND valid_until < #{now}
            """)
    List<Long> findExpiredContributionIds(@Param("now") LocalDateTime now);

    /**
     * **即将过期**、且还没用掉的券（每日批算里发提醒用）。
     *
     * <p>与 {@link #expireUnused} 的区间刚好接上又互不重叠：这条查 {@code [now, deadline)}，
     * 那条翻 {@code < now}。所以「提醒」与「翻状态」各管一段，不会同一张券既被提醒又被告知已过期。
     *
     * <p>只取 {@code status in (1, 2)}（未用/已锁定）：已核销与已过期的券不该被提醒——
     * 提醒一张已经用掉的券只会让用户去券包里找不到它。
     */
    @Select("""
            SELECT *
              FROM coupon
             WHERE is_deleted = 0
               AND status IN (1, 2)
               AND valid_until >= #{now}
               AND valid_until < #{deadline}
            ORDER BY valid_until ASC, id ASC
            """)
    List<Coupon> findExpiringUnused(@Param("now") LocalDateTime now,
                                    @Param("deadline") LocalDateTime deadline);
}
