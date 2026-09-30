package com.pethealth.provider.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pethealth.common.time.AppTime;
import com.pethealth.provider.domain.Provider;
import com.pethealth.provider.domain.ProviderQualification;
import com.pethealth.provider.domain.ProviderReviewLog;
import com.pethealth.provider.domain.ProviderServiceListing;
import com.pethealth.provider.mapper.ProviderMapper;
import com.pethealth.provider.mapper.ProviderQualificationMapper;
import com.pethealth.provider.mapper.ProviderServiceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 资质到期的每日批算（2026-09-29 项目所有者的口径，「到期软处理」）：
 *
 * <ol>
 *   <li><b>到期前 30 天提醒</b>：扫出 30 天内到期的材料，记一条 WARN。**这里没有新造通道**——
 *       真正的提醒要走消息中心与提醒体系（ph-reminder），而本切片不该改别的模块，
 *       所以对接点写在这里、投递留给协调项（见 ADR-0035 的「需要协调」）。
 *   <li><b>过期后自动下架</b>：服务者的材料**全部过期**时，把他的在架服务项统统改成已下架，
 *       但**不注销服务者**：历史订单、档案与客户关系都留着，补交材料后即可恢复上架。
 *       只留一条材料还查得着就下架太狠，所以判的是「还有没有一份没过期的」——
 *       与 {@code ProviderAccess.hasValidQualification} 是同一个判断，两处不能各写一套。
 * </ol>
 *
 * <p>每条被下架的服务项都留一行审核流水（{@code action=下架}，{@code actor_id=0} 表示系统写入）：
 * 「谁下的架」在服务者看来是个突发事件，必须查得出来是系统按规则做的，而不是某个运营随手点的。
 *
 * <p>批算**幂等**：没有在架服务项时什么也不做；同一天重复跑不会产生额外的流水
 * （第二次跑时服务项已经不是「已上架」了）。
 *
 * <p><b>触发在 {@link QualificationExpiryScheduler}</b>：{@code @Scheduled} 方法调本类的
 * {@link #sweep()} 是自调用，Spring 的事务代理不生效——「下架 + 写审核流水」会拆成两条独立提交，
 * 而测试注入 Bean 调用时是一个事务。触发分居两个 bean 才能让两条路径一致。
 */
@Component
public class QualificationExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(QualificationExpiryJob.class);

    /** 交付文档要求「资质到期前 30 天触发续期提醒」。 */
    public static final int RENEWAL_REMINDER_DAYS = 30;

    private final ProviderMapper providerMapper;
    private final ProviderQualificationMapper qualificationMapper;
    private final ProviderServiceMapper listingMapper;
    private final ReviewLogRecorder reviewLog;
    private final ProviderAccess access;

    public QualificationExpiryJob(ProviderMapper providerMapper,
                                  ProviderQualificationMapper qualificationMapper,
                                  ProviderServiceMapper listingMapper,
                                  ReviewLogRecorder reviewLog,
                                  ProviderAccess access) {
        this.providerMapper = providerMapper;
        this.qualificationMapper = qualificationMapper;
        this.listingMapper = listingMapper;
        this.reviewLog = reviewLog;
        this.access = access;
    }

    /**
     * 执行一次批算。抽出来是为了能被测试直接调用——批算没有 HTTP 入口，
     * 「只测 HTTP 缝」（ADR-0014）在这一个点上无法成立（理由写在测试类里）。
     */
    @Transactional
    public SweepResult sweep() {
        LocalDate today = AppTime.today();
        Set<Long> expiring = providersWithExpiringQualification(today);
        if (!expiring.isEmpty()) {
            // 真正的提醒要经 ph-reminder 写入消息中心（需要协调）；在此之前至少让日志里有痕迹
            log.warn("以下服务者的资质将在 {} 天内到期，需要续期提醒（消息中心投递待对接）：{}",
                    RENEWAL_REMINDER_DAYS, expiring);
        }

        Set<Long> expired = providersWithExpiredQualification(today);
        int delistedProviders = 0;
        int delistedListings = 0;
        for (Long providerId : expired) {
            Provider provider = providerMapper.selectById(providerId);
            if (provider == null || !provider.isApproved()) {
                continue;
            }
            // 「还有没有一份没过期的」——与上架门禁同一个判断，避免「自动下架了但自己又能上架」
            if (access.hasValidQualification(providerId)) {
                continue;
            }
            List<ProviderServiceListing> listed = listingMapper.selectList(
                    Wrappers.<ProviderServiceListing>lambdaQuery()
                            .eq(ProviderServiceListing::getProviderId, providerId)
                            .eq(ProviderServiceListing::getStatus, ProviderServiceListing.STATUS_LISTED));
            if (listed.isEmpty()) {
                continue;
            }
            for (ProviderServiceListing listing : listed) {
                listing.setStatus(ProviderServiceListing.STATUS_DELISTED);
                listingMapper.updateById(listing);
                reviewLog.record(ProviderReviewLog.TARGET_LISTING, listing.getId(), providerId,
                        ProviderReviewLog.ACTION_DELIST, "资质已过期，系统自动下架");
                delistedListings++;
            }
            delistedProviders++;
            log.warn("服务者 {} 的资质已全部过期，已自动下架 {} 个服务项（服务者未注销，补交材料后可恢复上架）",
                    providerId, listed.size());
        }
        return new SweepResult(expiring.size(), delistedProviders, delistedListings);
    }

    /** 30 天内到期（含今天到期）的材料所属服务者；被驳回的材料不算——它们已经不再有效。 */
    private Set<Long> providersWithExpiringQualification(LocalDate today) {
        LocalDate deadline = today.plusDays(RENEWAL_REMINDER_DAYS);
        return new HashSet<>(qualificationMapper.selectList(Wrappers.<ProviderQualification>lambdaQuery()
                        .isNotNull(ProviderQualification::getValidUntil)
                        .ge(ProviderQualification::getValidUntil, today)
                        .le(ProviderQualification::getValidUntil, deadline)
                        .ne(ProviderQualification::getStatus, ProviderQualification.STATUS_REJECTED))
                .stream().map(ProviderQualification::getProviderId).toList());
    }

    /** 已经过期的材料所属服务者（过期日早于今天）。 */
    private Set<Long> providersWithExpiredQualification(LocalDate today) {
        return new HashSet<>(qualificationMapper.selectList(Wrappers.<ProviderQualification>lambdaQuery()
                        .isNotNull(ProviderQualification::getValidUntil)
                        .lt(ProviderQualification::getValidUntil, today))
                .stream().map(ProviderQualification::getProviderId).toList());
    }

    /**
     * 一次批算的结果，用于日志与断言。
     *
     * @param expiringProviders  30 天内到期的服务者数（提醒用）
     * @param delistedProviders  本次被自动下架的服务者数
     * @param delistedListings   本次被自动下架的服务项数
     */
    public record SweepResult(int expiringProviders, int delistedProviders, int delistedListings) {
    }
}
