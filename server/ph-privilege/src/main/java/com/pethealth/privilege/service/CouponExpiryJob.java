package com.pethealth.privilege.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pethealth.catalog.api.Price;
import com.pethealth.common.time.AppTime;
import com.pethealth.privilege.config.PrivilegeProperties;
import com.pethealth.privilege.domain.Coupon;
import com.pethealth.privilege.domain.CouponContribution;
import com.pethealth.privilege.domain.CouponContributionLog;
import com.pethealth.privilege.mapper.CouponContributionLogMapper;
import com.pethealth.privilege.mapper.CouponContributionMapper;
import com.pethealth.privilege.mapper.CouponMapper;
import com.pethealth.api.reminder.BusinessMessageApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 券过期与额度释放的每日批算（ADR-0037 第三节：过期未核销**释放额度回池**）。
 *
 * <p>批算做两件事，顺序不能反：
 *
 * <ol>
 *   <li>先取「有过期券的贡献 id」（要在翻状态**之前**取，翻完就查不到候选了）；
 *   <li>把那些券翻成「已过期」，并为每条受影响的贡献记一条**额度释放流水**
 *       （{@code actor_id=0} 表示系统写入）——服务者问「我的额度怎么回来了」时，
 *       答案在流水里，而不是在一个突然变大的数字里。
 * </ol>
 *
 * <p><b>额度不是靠这次批算释放的</b>：可发放额度按「未过期未核销」实时算
 * （{@link CouponQuota}），所以哪怕批算没跑，一张券过期的那一刻额度就已经回来了。
 * 批算的作用是让状态与事实一致、并留下可追溯的释放记录。这条是本切片最重要的一个取舍：
 * **不把「额度正确」押在定时任务跑没跑上**。
 *
 * <p>幂等：重复跑第二次影响 0 行（状态已经是 4，取候选也取不到）。
 *
 * <p><b>触发在 {@link CouponExpiryScheduler}</b>：{@code @Scheduled} 方法调本类的
 * {@link #sweep()} 是自调用，Spring 的事务代理不生效（翻状态与写额度流水会分开提交），
 * 而测试注入 Bean 调用时是有事务的——触发分居两个 bean 才能让两条路径一致。
 */
@Component
public class CouponExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(CouponExpiryJob.class);

    private final CouponMapper couponMapper;
    private final CouponContributionMapper contributionMapper;
    private final CouponContributionLogMapper logMapper;
    private final BusinessMessageApi businessMessages;
    private final PrivilegeProperties properties;

    public CouponExpiryJob(CouponMapper couponMapper, CouponContributionMapper contributionMapper,
                           CouponContributionLogMapper logMapper, BusinessMessageApi businessMessages,
                           PrivilegeProperties properties) {
        this.couponMapper = couponMapper;
        this.contributionMapper = contributionMapper;
        this.logMapper = logMapper;
        this.businessMessages = businessMessages;
        this.properties = properties;
    }

    /**
     * 提醒**快过期**的券（F026 的「券过期提醒」）。
     *
     * <p><b>提醒的是「快过期」而不是「已经过期」</b>：已经过期时用户无事可做，那条消息只是通知坏消息；
     * 而「还有 N 天」才是能改变结果的那一条（去用掉）。口径与首页的券提醒条一致
     * （交付文档 5.1「您有 1 张洗护券，7 天后过期」）。天数走配置（{@code app.privilege.coupon-expiring-days}）。
     *
     * <p>幂等靠去重键 {@code coupon-expiring-{券 id}}：批算每天跑，同一张券只会收到一条。
     * 正在提醒窗口里的券如果用户一直不用，也不会被反复提醒——「一次」就是一次。
     */
    private int remindExpiringCoupons(LocalDateTime now) {
        LocalDateTime deadline = now.plusDays(properties.couponExpiringDays());
        List<Coupon> expiring = couponMapper.findExpiringUnused(now, deadline);
        for (Coupon coupon : expiring) {
            businessMessages.notify(new BusinessMessageApi.BusinessNotification(
                    coupon.getUserId(),
                    BusinessMessageApi.TYPE_COUPON,
                    "coupon-expiring-" + coupon.getId(),
                    "有一张券快过期了",
                    "面额 ¥" + Price.format(coupon.getFaceValue()) + " 的券将于 "
                            + coupon.getValidUntil().toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
                            + " 到期，可在支持的门店使用。",
                    "去券包看看",
                    "/coupons"));
        }
        return expiring.size();
    }

    /**
     * 执行一次批算。抽出来是为了能被测试直接调用——批算没有 HTTP 入口，
     * 「只测 HTTP 缝」（ADR-0014）在这一处无法成立，理由与资质批算相同。
     */
    @Transactional
    public SweepResult sweep() {
        LocalDateTime now = AppTime.now();
        List<Long> affected = couponMapper.findExpiredContributionIds(now);
        // 提醒先在翻状态之前发：翻完之后这些券就不在「即将过期」的窗口里了（两段区间互不重叠）
        int expiring = remindExpiringCoupons(now);
        int expired = couponMapper.expireUnused(now);
        int released = 0;
        for (Long contributionId : affected) {
            CouponContribution contribution = contributionMapper.selectById(contributionId);
            if (contribution == null) {
                continue;
            }
            CouponContributionLog logRow = new CouponContributionLog();
            logRow.setContributionId(contribution.getId());
            logRow.setProviderId(contribution.getProviderId());
            logRow.setAction(CouponContributionLog.ACTION_RELEASE);
            logRow.setTotalCount(contribution.getTotalCount());
            logRow.setAvailableCount(availableOf(contribution.getId()));
            logRow.setRemark("过期未核销，额度释放回池");
            logMapper.insert(logRow);
            released++;
        }
        return new SweepResult(expiring, expired, released);
    }

    private int availableOf(long contributionId) {
        LocalDateTime now = AppTime.now();
        CouponContribution contribution = contributionMapper.selectById(contributionId);
        return CouponQuota.of(contribution, couponMapper.statOfContribution(contributionId, now)).available();
    }

    /**
     * 一次批算的结果。
     *
     * @param expiringCoupons       落在「快过期」提醒窗口里的券数（发送本身按去重键幂等，
     *                              所以这个数**是候选数不是新增消息数**——每天跑都会数到同一张券）
     * @param expiredCoupons        本次被标记为「已过期」的券数
     * @param releasedContributions 记了额度释放流水的贡献条数
     */
    public record SweepResult(int expiringCoupons, int expiredCoupons, int releasedContributions) {
    }

    /** 供测试/运营确认：某条贡献当前还有多少可发放（就是额度公式的结果）。 */
    @Transactional(readOnly = true)
    public int availableOfContribution(long contributionId) {
        CouponContribution contribution = contributionMapper.selectOne(
                Wrappers.<CouponContribution>lambdaQuery().eq(CouponContribution::getId, contributionId));
        if (contribution == null) {
            return 0;
        }
        return CouponQuota.of(contribution,
                couponMapper.statOfContribution(contributionId, AppTime.now())).available();
    }
}
