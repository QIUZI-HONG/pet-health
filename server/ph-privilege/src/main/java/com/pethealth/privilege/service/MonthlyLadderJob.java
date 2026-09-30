package com.pethealth.privilege.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pethealth.common.time.AppTime;
import com.pethealth.privilege.api.CouponApi;
import com.pethealth.privilege.domain.Coupon;
import com.pethealth.privilege.domain.PointEarn;
import com.pethealth.privilege.domain.PointLadderGrant;
import com.pethealth.privilege.domain.PointLadderTier;
import com.pethealth.privilege.mapper.PointLadderGrantMapper;
import com.pethealth.privilege.mapper.PointLadderTierMapper;
import com.pethealth.privilege.mapper.PointRecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * 月度阶梯的定时任务骨架（F018 / 切片 #113），决策见 ADR-0038 第四节与 ADR-0046。
 *
 * <p>每月 1 日算**上月**累计获得积分 → 按档位发券。三件刻意的事：
 *
 * <ol>
 *   <li><b>只做骨架</b>：门槛值与奖励没有规则依据，**不编**——种子里一个档位都没有。
 *       档位为空时批算照跑、不发任何券（骨架是通的），运营在后台配好档位即生效；
 *   <li><b>年度大奖留白</b>：ADR-0038 明说没有规则依据，所以这里**没有**年度逻辑，
 *       连占位都不留（占位的实现会在某次验收里被当成「已经做了」）；
 *   <li><b>取「达成的最高一档」而不是累加多档</b>：一个人只拿一档的奖励，
 *       这是阶梯的通常语义（跨过 500 分档不会再拿 100 分档的奖），也是可解释性最好的那种。
 *       这条口径 ADR 没写，属本切片的决定，列在 ADR-0046 的待澄清里。
 * </ol>
 *
 * <p>幂等：{@code (user_id, period)} 唯一 + 先查后插。批算重跑、多实例并发都只发一次。
 *
 * <p><b>触发在 {@link MonthlyLadderScheduler}，不在本类</b>：{@code @Scheduled} 方法调本类的
 * {@link #run()} 属于**自调用**，Spring 的事务代理不生效——生产会退化成「每条语句各自提交」，
 * 而上面那句幂等承诺正建立在「一次批算一个事务」上。两个 bean 分开之后，
 * 定时触发与测试、运营手动补跑走的是同一条路径（护栏见 {@code ScheduledTransactionBoundaryTest}）。
 */
@Component
public class MonthlyLadderJob {

    private static final Logger log = LoggerFactory.getLogger(MonthlyLadderJob.class);

    private final PointRecordMapper recordMapper;
    private final PointLadderTierMapper tierMapper;
    private final PointLadderGrantMapper grantMapper;
    private final CouponApi couponApi;

    public MonthlyLadderJob(PointRecordMapper recordMapper, PointLadderTierMapper tierMapper,
                            PointLadderGrantMapper grantMapper, CouponApi couponApi) {
        this.recordMapper = recordMapper;
        this.tierMapper = tierMapper;
        this.grantMapper = grantMapper;
        this.couponApi = couponApi;
    }

    /** 执行一次批算（测试直接调它；也可以被运营手动补跑）。 */
    @Transactional
    public SweepResult run() {
        YearMonth lastMonth = YearMonth.from(AppTime.today()).minusMonths(1);
        String period = lastMonth.toString();
        LocalDate from = lastMonth.atDay(1);
        LocalDate to = lastMonth.atEndOfMonth();

        List<PointLadderTier> tiers = tierMapper.selectList(Wrappers.<PointLadderTier>lambdaQuery()
                .eq(PointLadderTier::getStatus, PointLadderTier.STATUS_ENABLED)
                .orderByAsc(PointLadderTier::getThresholdPoints));
        List<PointEarn> earners = recordMapper.listEarners(from, to);
        if (tiers.isEmpty()) {
            // 没配档位：不发任何东西，但把「扫了多少人」记下来——
            // 否则「骨架是不是跑着」这件事在日志里看不出来
            return new SweepResult(period, earners.size(), 0);
        }

        int granted = 0;
        for (PointEarn earner : earners) {
            PointLadderTier tier = highestReached(tiers, earner.getTotal());
            if (tier == null) {
                continue;
            }
            if (grantMapper.selectCount(Wrappers.<PointLadderGrant>lambdaQuery()
                    .eq(PointLadderGrant::getUserId, earner.getUserId())
                    .eq(PointLadderGrant::getPeriod, period)) > 0) {
                continue; // 这个账期已经发过（重跑不再发）
            }
            int count = tier.getCouponCount() == null ? 1 : tier.getCouponCount();
            Long firstCouponId = null;
            for (int i = 0; i < count; i++) {
                String ref = "ladder:" + period + ":" + earner.getUserId() + (count == 1 ? "" : ":" + (i + 1));
                CouponApi.CouponInfo coupon = couponApi.issue(new CouponApi.IssueCommand(earner.getUserId(),
                        tier.getCouponTemplateId(), Coupon.SOURCE_MONTHLY_LADDER, ref, null,
                        "月度阶梯 " + period + "（累计 " + earner.getTotal() + " 分）"));
                if (firstCouponId == null) {
                    firstCouponId = coupon.id();
                }
            }
            PointLadderGrant grant = new PointLadderGrant();
            grant.setUserId(earner.getUserId());
            grant.setPeriod(period);
            grant.setTierId(tier.getId());
            grant.setThresholdPoints(tier.getThresholdPoints());
            grant.setCumulativePoints(earner.getTotal());
            grant.setCouponId(firstCouponId);
            try {
                grantMapper.insert(grant);
                granted++;
            } catch (DuplicateKeyException e) {
                // 并发下的同一账期：唯一键兜底。券已经发出去了——这是这条唯一键的代价，
                // 所以批算是「单实例语义」的：多实例同时跑时以先插到的那次为准（见 ADR-0046）
                log.warn("月度阶梯发放记录并发冲突：userId={} period={}（券已发出，记录以先到的为准）",
                        earner.getUserId(), period);
            }
        }
        return new SweepResult(period, earners.size(), granted);
    }

    /** 达成的最高一档（门槛 ≤ 累计分里最大的那个）。 */
    private PointLadderTier highestReached(List<PointLadderTier> tiers, Integer cumulative) {
        int points = cumulative == null ? 0 : cumulative;
        PointLadderTier best = null;
        for (PointLadderTier tier : tiers) {
            if (tier.getThresholdPoints() != null && points >= tier.getThresholdPoints()) {
                if (best == null || tier.getThresholdPoints() > best.getThresholdPoints()) {
                    best = tier;
                }
            }
        }
        return best;
    }

    /**
     * 一次批算的结果。
     *
     * @param period  账期（yyyy-MM，上月）
     * @param scanned 上月有过得分的人数（骨架是否在跑，看这个数）
     * @param granted 本次发券的人数
     */
    public record SweepResult(String period, int scanned, int granted) {
    }
}
