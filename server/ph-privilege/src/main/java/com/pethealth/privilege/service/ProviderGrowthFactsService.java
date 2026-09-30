package com.pethealth.privilege.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pethealth.api.privilege.ProviderGrowthFactsApi;
import com.pethealth.common.time.AppTime;
import com.pethealth.privilege.domain.ContributionCouponStat;
import com.pethealth.privilege.domain.Coupon;
import com.pethealth.privilege.domain.CouponContribution;
import com.pethealth.privilege.domain.CouponTemplate;
import com.pethealth.privilege.domain.InviteRelation;
import com.pethealth.privilege.domain.ProviderInviteCode;
import com.pethealth.privilege.mapper.CouponContributionMapper;
import com.pethealth.privilege.mapper.CouponMapper;
import com.pethealth.privilege.mapper.CouponTemplateMapper;
import com.pethealth.privilege.mapper.InviteRelationMapper;
import com.pethealth.privilege.mapper.ProviderInviteCodeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link ProviderGrowthFactsApi} 的**生产实现**（ADR-0052「需要协调」第 1 条的清偿）。
 *
 * <p>在此之前，考核的拉新与券两项取不到数，只能按「未参与、重算权重、明细里注明」处理。
 * 这个类把两半都接上：
 *
 * <ul>
 *   <li><b>券那一半</b>读的是现成的两张表：{@code coupon_contribution}（承诺额度）与
 *       {@code coupon}（核销数）。完成率的算法**不在这里重写**——它收在
 *       {@link CouponQuota}，这里只把同一口径的数加总（见 {@link #couponCompletionRate}）；
 *   <li><b>拉新那一半</b>读 {@code invite_relation.inviter_provider_id}（V44 起的门店归因），
 *       而「平台有没有给这家店拉新入口」的判据是**这家店有没有推广码**——
 *       没有码就回 {@code null}（无该维度要求），有码但一条有效邀请都没有才回 {@code 0}
 *       （该做而没做）。这两个值在考核里是两种事实，不能混（见接口的类注释）。
 * </ul>
 *
 * <p><b>为什么用 {@link AppTime#now()} 而不是传进来的时刻</b>：完成率要判「哪些券还在占用中」，
 * 那是相对当前时刻的。考核账期是历史月份，用它去判占用会把已过期的券算成仍占着额度。
 * 口径与 {@code CouponExpiryJob} 一致：占用与否看**现在**。
 */
@Service
public class ProviderGrowthFactsService implements ProviderGrowthFactsApi {

    private static final Logger log = LoggerFactory.getLogger(ProviderGrowthFactsService.class);

    private final InviteRelationMapper relationMapper;
    private final ProviderInviteCodeMapper inviteCodeMapper;
    private final CouponTemplateMapper templateMapper;
    private final CouponContributionMapper contributionMapper;
    private final CouponMapper couponMapper;

    public ProviderGrowthFactsService(InviteRelationMapper relationMapper,
                                      ProviderInviteCodeMapper inviteCodeMapper,
                                      CouponTemplateMapper templateMapper,
                                      CouponContributionMapper contributionMapper,
                                      CouponMapper couponMapper) {
        this.relationMapper = relationMapper;
        this.inviteCodeMapper = inviteCodeMapper;
        this.templateMapper = templateMapper;
        this.contributionMapper = contributionMapper;
        this.couponMapper = couponMapper;
    }

    /**
     * 有效邀请数：区间内**结算为有效**的门店归因关系数。
     *
     * <p>判据用 {@code settled_at}（判定有效的那一刻）而不是 {@code attributed_at}（注册那一刻）：
     * 考核算的是「这个账期里这家店真的拉到了多少人」，而一条邀请要等 24 小时观察窗结束
     * 才知道算不算数。用注册时间会让「月底注册、下月初判定有效」的那批人算进上个月。
     */
    @Override
    @Transactional(readOnly = true)
    public Integer effectiveInvites(long providerId, LocalDate from, LocalDate to) {
        if (!hasInviteEntry(providerId)) {
            return null;
        }
        Long total = relationMapper.selectCount(Wrappers.<InviteRelation>lambdaQuery()
                .eq(InviteRelation::getInviterProviderId, providerId)
                .eq(InviteRelation::getStatus, InviteRelation.STATUS_EFFECTIVE)
                .ge(InviteRelation::getSettledAt, from.atStartOfDay())
                .lt(InviteRelation::getSettledAt, to.plusDays(1).atStartOfDay()));
        return total == null ? 0 : total.intValue();
    }

    /**
     * 可贡献的券模板数：{@code cost_bearer = 1}（服务者成本）且启用中的模板。
     *
     * <p>不按门店的适用范围过滤——取值范围只影响「券能不能被用户用掉」，不影响
     * 「平台有没有给出券的出口」这个事实（接口注释里的同一条）。
     */
    @Override
    @Transactional(readOnly = true)
    public int contributableCouponTemplates() {
        Long total = templateMapper.selectCount(Wrappers.<CouponTemplate>lambdaQuery()
                .eq(CouponTemplate::getCostBearer, CouponTemplate.COST_PROVIDER)
                .eq(CouponTemplate::getStatus, CouponTemplate.STATUS_ENABLED));
        return total == null ? 0 : total.intValue();
    }

    /**
     * 券核销数：{@code coupon.provider_id = 本店} 且已核销、核销时间落在区间内。
     *
     * <p>{@code provider_id} 在发放时就写死了核销门店（服务者贡献券只在本店核销），
     * 所以这个数天然就是「用户拿着本店贡献的券到店用掉了多少张」，与钱无关（ADR-0036）。
     */
    @Override
    @Transactional(readOnly = true)
    public int couponRedeemedCount(long providerId, LocalDate from, LocalDate to) {
        Long total = couponMapper.selectCount(Wrappers.<Coupon>lambdaQuery()
                .eq(Coupon::getProviderId, providerId)
                .eq(Coupon::getStatus, Coupon.STATUS_REDEEMED)
                .ge(Coupon::getRedeemedAt, from.atStartOfDay())
                .lt(Coupon::getRedeemedAt, to.plusDays(1).atStartOfDay()));
        return total == null ? 0 : total.intValue();
    }

    /**
     * 贡献额度完成率 = Σ已核销 ÷ Σ承诺额度（两位小数）。
     *
     * <p><b>为什么是「先加总再相除」而不是「每条贡献各算各的完成率再平均」</b>：
     * 一家店可能既承诺了 100 张洗护券、又承诺了 1 张疫苗券。逐条算完成率再平均会让那一张
     * 疫苗券的权重与 100 张洗护券一样大——一条小额度能把整体完成率拉低到与它的承诺量不相称的位置。
     * 分母用 Σ承诺额度、分子用 Σ已核销，算出来才是「这家店承诺了 101 张、核销了 60 张」。
     *
     * <p><b>账期参数在这里不参与计算，是刻意的</b>：承诺额度是「这家店答应出多少张」，
     * 它不按月重置（ADR-0044 第三节：调额与撤回才是它的生命周期）。所以完成率是**累计口径**，
     * 与 {@link CouponQuota#completionRate()} 一致。账期的影响由同一个考核项里的
     * {@link #couponRedeemedCount}（按核销时间落在区间内取数）承担——
     * 考核算的是「完成率 × 本账期核销数」，一个累计、一个当期，两者相乘才是那条口径。
     *
     * <p>逐条取统计（{@link CouponMapper#statsOfContributions} 批量版）而不是一次聚合 SQL：
     * 一家店的贡献条数是个位数，而额度的口径必须与 {@link CouponQuota}
     * （已核销 = 状态为已核销的券）保持同一处。多写一条聚合 SQL 就等于多一处口径。
     *
     * @return 0.00–1.00 之间两位小数；本店一条贡献都没有时回 {@code 0}
     */
    @Override
    @Transactional(readOnly = true)
    public BigDecimal couponCompletionRate(long providerId, LocalDate from, LocalDate to) {
        List<CouponContribution> contributions = contributionMapper.selectList(
                Wrappers.<CouponContribution>lambdaQuery().eq(CouponContribution::getProviderId, providerId));
        if (contributions.isEmpty()) {
            return BigDecimal.ZERO.setScale(2);
        }
        List<Long> ids = contributions.stream().map(CouponContribution::getId).toList();
        Map<Long, ContributionCouponStat> stats = new HashMap<>();
        for (ContributionCouponStat stat : couponMapper.statsOfContributions(ids, AppTime.now())) {
            stats.put(stat.getContributionId(), stat);
        }
        int total = 0;
        int redeemed = 0;
        for (CouponContribution contribution : contributions) {
            total += contribution.getTotalCount() == null ? 0 : contribution.getTotalCount();
            ContributionCouponStat stat = stats.get(contribution.getId());
            redeemed += stat == null || stat.getRedeemed() == null ? 0 : stat.getRedeemed();
        }
        if (total <= 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        BigDecimal rate = BigDecimal.valueOf(redeemed)
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP)
                .setScale(2, RoundingMode.HALF_UP);
        if (rate.compareTo(BigDecimal.ONE) > 0) {
            // 理论上不会出现（可发放是按「未核销」算的），真出现说明有人绕过了 CouponQuota，
            // 报出来而不是悄悄截断——考核按「完成率 × 核销数」算分，大于 1 会让分数虚高
            log.warn("券完成率大于 1：providerId={} 已核销 {} / 承诺 {}（说明有写入绕过了 CouponQuota）",
                    providerId, redeemed, total);
            return BigDecimal.ONE.setScale(2);
        }
        return rate;
    }

    /** 「平台有没有给这家店拉新入口」= 这家店有没有推广码（V44）。 */
    private boolean hasInviteEntry(long providerId) {
        Long total = inviteCodeMapper.selectCount(Wrappers.<ProviderInviteCode>lambdaQuery()
                .eq(ProviderInviteCode::getProviderId, providerId));
        return total != null && total > 0;
    }
}
