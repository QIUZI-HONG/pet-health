package com.pethealth.api.privilege;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * **服务者考核（F022）要的增长侧事实**：拉新与券（ADR-0039 第三节、ADR-0044 第五节）。
 *
 * <p>为什么需要它：考核的三项里，过程分从 {@code ph-order} 的只读统计拿
 * （{@link com.pethealth.order.api.OrderStatsApi}），而**拉新 = 该服务者的有效邀请数**、
 * **券 = 贡献额度完成率 × 核销数**这两个数在 {@code ph-privilege} 手里。ADR-0006 禁止
 * 跨模块 join 别人的表，所以走这个只读接口——形状与 {@code ConsultStats} / {@code ProviderAccessApi}
 * 是同一处位置调整（接口在 ph-api，实现只能由表的拥有者写）。
 *
 * <p><b>实现由 ph-privilege 提供（本次交付未接线，见 ADR-0052 的「需要协调」）</b>：
 * 拉新那一半要先把「服务者的拉新入口」（店内二维码 / 推广码把用户绑定到门店）落下来——
 * 交付文档 F022 写了这个链路，而邀请关系现在只有用户对用户（{@code invite_relation.inviter_user_id}
 * 是用户，不是门店），所以**这条口径本身也还没定**（ADR-0052 的「需要协调」第 1 条）。
 * 券那一半是现成的读：{@code coupon_contribution} 的额度账与 {@code coupon} 的核销数
 * （{@code CouponQuota} 已经把完成率的算法收在一处）。
 *
 * <p>在它落地之前：考核算分**不会**把这些项静默按满分或 0 分处理，而是按 ADR-0050 第四节
 * 的第二种口径——**不参与、重算权重、明细里注明「未参与（数据源未接线）」**。
 * 这与「平台侧无该维度要求」在结果上一致（都是不参与），但原因写在明细里，
 * 事后查得到是哪一种（拿不准就报出来，别编一个数）。
 *
 * <p><b>计数回 0、没有该维度回 null</b>：与 OrderStatsApi 同一条纪律——0 与 null 在考核里
 * 是两种事实。这里的 {@code null} 表示「平台侧还没有给这个服务者拉新的入口」，
 * 而 {@code 0} 表示「有入口但一条有效邀请都没有」（后者按缺项第一口径记 0 分）。
 */
public interface ProviderGrowthFactsApi {

    /**
     * 该服务者在区间内的**有效邀请数**（拉新）。
     *
     * <p>有效邀请的口径是 ADR-0039 第一节的那条：被邀请人**完成建档且 24 小时内有行为**
     * （{@code invite_relation.status = 2}），不是注册数。
     *
     * @return 有效邀请数；**平台侧还没有给这个服务者拉新入口时回 {@code null}**（无该维度要求）
     */
    Integer effectiveInvites(long providerId, LocalDate from, LocalDate to);

    /**
     * 平台券池里**可被服务者贡献**的券模板数（{@code cost_bearer = 1} 且启用中，ADR-0044 第一节）。
     *
     * <p>这是「平台有没有给出券的机会」这条判据的来源（ADR-0050 第四节举的例子正是它）：
     * 池子里一张可贡献的券都没有 → 该品类根本没有券可出 → 券维度**不参与**；
     * 有券可出而服务者一张没核销 → 那是「该做而没做」→ 记 0 分。
     *
     * <p>不按门店的适用范围过滤：取值范围只影响「券能不能被用户用掉」，不影响
     * 「平台有没有给出券的出口」这个事实。
     */
    int contributableCouponTemplates();

    /**
     * 该服务者在区间内的**券核销数**（{@code coupon.redeemed_at} 落在区间内、核销门店是本店）。
     *
     * <p>券的核销 ≠ 收款（ADR-0036）：这里数的是「用户拿着本店贡献的券到店用掉了多少张」，
     * 与钱无关。
     *
     * @return 核销张数；一张都没有回 {@code 0}
     */
    int couponRedeemedCount(long providerId, LocalDate from, LocalDate to);

    /**
     * 该服务者在区间内的**贡献额度完成率** = 已核销 ÷ 承诺额度（ADR-0044 第一节的算法，
     * 两位小数）。
     *
     * <p>它单独看没有意义——考核的口径是 **完成率 × 核销数**（ADR-0039 第三节），
     * 两者一起才既惩罚「承诺了不核销」也惩罚「一张不接」。所以这个接口把两个数都给出来，
     * 由考核侧做乘法（口径收在考核一处，不在这两处各放一半）。
     *
     * @return 完成率（{@code 0.00}–{@code 1.00} 之外的取值不出现，两位小数）；本店一条贡献都没有时回 {@code 0}
     */
    BigDecimal couponCompletionRate(long providerId, LocalDate from, LocalDate to);
}
