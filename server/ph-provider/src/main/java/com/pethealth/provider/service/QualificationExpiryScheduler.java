package com.pethealth.provider.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 资质到期批算的**触发**：只负责到点叫一次，规则全在 {@link QualificationExpiryJob}。
 *
 * <p>与 {@code MonthlyLadderScheduler} / {@code CouponExpiryScheduler} 同一个理由：
 * {@code @Scheduled} 方法不能与它要调的 {@code @Transactional} 方法同处一个类
 * ——自调用绕过代理，事务静默失效，而测试走的是代理。见 {@code ScheduledTransactionBoundaryTest}。
 */
@Component
public class QualificationExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(QualificationExpiryScheduler.class);

    private final QualificationExpiryJob job;

    public QualificationExpiryScheduler(QualificationExpiryJob job) {
        this.job = job;
    }

    /** 每天 03:30（低峰）跑一次；时间可配，便于运维错峰。 */
    @Scheduled(cron = "${app.provider.qualification-expiry-cron:0 30 3 * * *}")
    public void scheduled() {
        QualificationExpiryJob.SweepResult result = job.sweep();
        log.info("资质到期批算完成：30 天内到期 {} 家，本次下架 {} 家的 {} 个服务项",
                result.expiringProviders(), result.delistedProviders(), result.delistedListings());
    }
}
