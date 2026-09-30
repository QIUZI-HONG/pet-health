package com.pethealth.privilege.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 券过期批算的**触发**（F026 / F015）：只负责到点叫一次，规则全在 {@link CouponExpiryJob}。
 *
 * <p>与 {@link MonthlyLadderScheduler} 同一个理由：{@code @Scheduled} 方法不能与它要调的
 * {@code @Transactional} 方法同处一个类——自调用绕过代理，事务静默失效，而测试走的是代理。
 * 见 {@code ScheduledTransactionBoundaryTest}。
 */
@Component
public class CouponExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(CouponExpiryScheduler.class);

    private final CouponExpiryJob job;

    public CouponExpiryScheduler(CouponExpiryJob job) {
        this.job = job;
    }

    /** 每天 01:10（低峰）跑一次；与资质批算（03:30）、权益回收（00:40）错开。 */
    @Scheduled(cron = "${app.privilege.coupon-expiry-cron:0 10 1 * * *}", zone = "Asia/Shanghai")
    public void scheduled() {
        CouponExpiryJob.SweepResult result = job.sweep();
        log.info("券过期批算完成：提醒窗口内 {} 张（按去重键发送）、过期 {} 张，涉及 {} 条贡献（额度已释放回池）",
                result.expiringCoupons(), result.expiredCoupons(), result.releasedContributions());
    }
}
