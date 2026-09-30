package com.pethealth.privilege.service;

import com.pethealth.privilege.api.RightsApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 权益到期的每日回收（ADR-0038 第三节：**到期只回收该来源授予的那一条**）。
 *
 * <p>为什么要有这个批算：判定本身是实时的（{@link RightsEngine} 里按 {@code expire_at}
 * 判生效），所以即使批算没跑，用户也不会用到一个过期的权益。批算做的是**收尾**：
 * 把状态从「生效」改成「已回收」，让授予记录与事实一致——
 * 否则那些记录会永远停在「生效」，运营看数据时数不清「当前到底有多少条在生效」。
 *
 * <p>订阅到期不会碰到邀请得的永久权益：那一条没有到期时间，条件更新的
 * {@code expire_at IS NOT NULL AND expire_at < now} 根本不会命中它。这是「只回收该来源那一条」
 * 在实现上最省事也最不容易写错的形式——**不是靠逐条判断来源，而是靠条件本身**。
 */
@Component
public class RightsExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(RightsExpiryJob.class);

    private final RightsApi rightsApi;

    public RightsExpiryJob(RightsApi rightsApi) {
        this.rightsApi = rightsApi;
    }

    /** 每天 00:40 回收一次（与资质批算 03:30、券过期批算 01:10 错开）。 */
    @Scheduled(cron = "${app.privilege.rights-expiry-cron:0 40 0 * * *}", zone = "Asia/Shanghai")
    public void scheduled() {
        int revoked = rightsApi.revokeExpired();
        if (revoked > 0) {
            log.info("权益到期回收完成：{} 条", revoked);
        }
    }
}
