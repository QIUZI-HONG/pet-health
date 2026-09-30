package com.pethealth.privilege.service;

import com.pethealth.privilege.api.InviteAttributionApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 邀请结算的批算（每小时一次）：把观察窗（24 小时）已满的待生效关系判成有效或无效。
 *
 * <p>为什么是每小时而不是每天：观察窗是 24 小时，按天跑会让实际等待变成 24–48 小时，
 * 而用户对「邀请奖励什么时候到」的忍耐窗口远小于一天。每小时的量很小（只看待结算的那几条）。
 *
 * <p>批算没有 HTTP 入口，测试直接调 {@link InviteAttributionApi#settle}（同资质批算的取舍）。
 */
@Component
public class InviteSettlementJob {

    private static final Logger log = LoggerFactory.getLogger(InviteSettlementJob.class);

    /** 单批上限：一次锁太多行会拖住同一张表上的注册写入。 */
    private static final int BATCH = 200;

    private final InviteAttributionApi inviteApi;

    public InviteSettlementJob(InviteAttributionApi inviteApi) {
        this.inviteApi = inviteApi;
    }

    /** 每小时的第 5 分钟跑一次（避开整点的其它批算）。 */
    @Scheduled(cron = "${app.privilege.invite-settle-cron:0 5 * * * *}", zone = "Asia/Shanghai")
    public void scheduled() {
        InviteAttributionApi.SettlementResult result = inviteApi.settle(BATCH);
        if (result.scanned() > 0) {
            log.info("邀请结算批算：扫描 {} 条，有效 {}，无效 {}",
                    result.scanned(), result.effective(), result.invalid());
        }
    }
}
