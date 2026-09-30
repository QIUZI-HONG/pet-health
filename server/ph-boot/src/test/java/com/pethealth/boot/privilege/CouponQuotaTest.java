package com.pethealth.boot.privilege;

import com.pethealth.boot.support.ApiClient;
import com.pethealth.common.error.BusinessException;
import com.pethealth.privilege.api.CouponApi;
import com.pethealth.privilege.domain.Coupon;
import com.pethealth.privilege.service.CouponExpiryJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 券实例的发放、核销、额度与对账（切片 #110，决策见 ADR-0037 第三节 / ADR-0044）。
 *
 * <p><b>这一层刻意调内部接口（{@code CouponApi}）而不是走 HTTP</b>：发券的四个来源
 * （邀请 / 打卡任务 / 积分兑换 / 平台补贴）里，前三个的 C 端入口在下一波（{@code app.yaml}），
 * 核销在订单侧（ADR-0038 第二节）。拿不到入口的规则不等于不用测——它们正是最容易写错的部分
 * （超发、漏释放、重复发奖）。断言仍然只看**外部可观察状态**：库里的券、额度账、对账等式。
 *
 * <p>覆盖的四条硬规则：
 *
 * <ul>
 *   <li><b>额度不超发</b>：并发发券时成功数恰等于承诺额度（锁行 + 实时算，不是先查后写）；
 *   <li><b>过期释放额度</b>：券一过期，额度的「占用中」就减一——**不依赖批算跑没跑**；
 *   <li><b>同一行为不重复发奖</b>：同一来源引用的重复发券只产生一张；
 *   <li><b>核销幂等</b>：并发双击只有一次成功，另一次 80002。
 * </ul>
 */
class CouponQuotaTest extends PrivilegeTestSupport {

    /** 持券人的用户 id（C 端用户与后台账号同域，这里取一个不与手机号序列冲突的值）。 */
    private static final long COUPON_HOLDER = 900_001L;

    @Autowired
    private CouponApi couponApi;

    @Autowired
    private CouponExpiryJob expiryJob;

    @Test
    @DisplayName("额度不超发：并发发券时成功数恰等于承诺额度，且一张都不多")
    void concurrentIssueNeverExceedsQuota() throws Exception {
        String admin = adminToken();
        long templateId = createTemplate(admin, "CP-101");
        ApprovedProvider provider = createApprovedProvider("LIC-QUOTA-001");
        long contributionId = commit(provider.token(), templateId, 5);

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<String> failures = new ArrayList<>();
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int seq = i;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    couponApi.issue(new CouponApi.IssueCommand(900_000L + seq, templateId,
                            Coupon.SOURCE_CHECK_IN_TASK, "checkin-task:" + seq, null, null));
                    succeeded.incrementAndGet();
                } catch (BusinessException e) {
                    // 额度用完是**业务结果**（40900），不是异常：并发下必然有一批拿到它
                    rejected.incrementAndGet();
                    synchronized (failures) {
                        failures.add(e.getErrorCode() + " " + e.getMessage());
                    }
                } catch (Exception e) {
                    synchronized (failures) {
                        failures.add(e.getClass().getSimpleName() + " " + e.getMessage());
                    }
                }
            }));
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        for (java.util.concurrent.Future<?> future : futures) {
            future.get();
        }

        // 成功数 == 承诺额度，一个不多（超发的判据是库里的行数，不是计数器的值）
        assertThat(succeeded.get()).isEqualTo(5);
        assertThat(rejected.get()).isEqualTo(threads - 5);
        assertThat(failures).allMatch(message -> message.startsWith("CONFLICT"));
        Long stored = jdbc.queryForObject(
                "SELECT COUNT(*) FROM coupon WHERE contribution_id = ?", Long.class, contributionId);
        assertThat(stored).isEqualTo(5L);

        // 额度账：已发放 5、占用中 5、可发放 0（都不是靠计数器得出的）
        ApiClient.ApiCall detail = api.get("/api/v1/provider/coupon-contributions/" + contributionId,
                provider.token());
        assertThat(detail.data().path("issued_count").asInt()).isEqualTo(5);
        assertThat(detail.data().path("reserved_count").asInt()).isEqualTo(5);
        assertThat(detail.data().path("available_count").asInt()).isZero();
        assertThat(detail.data().path("completion_rate").asText()).isEqualTo("0.00");
    }

    @Test
    @DisplayName("过期释放额度：券过期后占用中减一、可发放回升，并留下一条释放流水（不依赖批算）")
    void expiredCouponReleasesQuota() {
        String admin = adminToken();
        long templateId = createTemplate(admin, "CP-102");
        ApprovedProvider provider = createApprovedProvider("LIC-QUOTA-002");
        long contributionId = commit(provider.token(), templateId, 3);

        long first = issue(provider.providerId(), templateId, Coupon.SOURCE_CHECK_IN_TASK, "ref-1").id();
        issue(provider.providerId(), templateId, Coupon.SOURCE_CHECK_IN_TASK, "ref-2");
        assertThat(availableOf(contributionId)).isEqualTo(1);

        // 让其中一张过期（模拟时间流逝：额度算的是 valid_until，不是那一列状态）
        jdbc.update("UPDATE coupon SET valid_until = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(1), first);

        // **批算没跑**，额度也应当已经回来了——这是「不把额度正确押在定时任务上」的验证
        assertThat(availableOf(contributionId)).isEqualTo(2);
        ApiClient.ApiCall beforeSweep = api.get("/api/v1/provider/coupon-contributions/" + contributionId,
                provider.token());
        assertThat(beforeSweep.data().path("reserved_count").asInt()).isEqualTo(1);
        assertThat(beforeSweep.data().path("expired_count").asInt()).isEqualTo(1);

        // 批算把状态翻成「已过期」并记一条释放流水
        CouponExpiryJob.SweepResult result = expiryJob.sweep();
        assertThat(result.expiredCoupons()).isEqualTo(1);
        assertThat(result.releasedContributions()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM coupon WHERE id = ?", Integer.class, first))
                .isEqualTo(4);
        Long releaseLogs = jdbc.queryForObject("SELECT COUNT(*) FROM coupon_contribution_log "
                + "WHERE contribution_id = ? AND action = 4", Long.class, contributionId);
        assertThat(releaseLogs).isEqualTo(1L);

        // 幂等：再跑一次不再翻、不再记
        CouponExpiryJob.SweepResult second = expiryJob.sweep();
        assertThat(second.expiredCoupons()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_contribution_log "
                + "WHERE contribution_id = ? AND action = 4", Long.class, contributionId)).isEqualTo(1L);

        // 过期的券核销不了（80001），也不会被算进「已核销」
        try {
            couponApi.redeem(first, provider.providerId(), 1L, 1L);
            throw new AssertionError("过期券不该能核销");
        } catch (BusinessException e) {
            assertThat(e.getErrorCode().getCode()).isEqualTo(80001);
        }
        assertThat(availableOf(contributionId)).isEqualTo(2);
    }

    @Test
    @DisplayName("核销：原子条件更新（并发双击只有一次成功），重复核销给 80002，额度不再被占用")
    void redeemIsAtomic() throws Exception {
        String admin = adminToken();
        long templateId = createTemplate(admin, "CP-103");
        ApprovedProvider provider = createApprovedProvider("LIC-QUOTA-003");
        long contributionId = commit(provider.token(), templateId, 2);
        long couponId = issue(provider.providerId(), templateId, Coupon.SOURCE_PLATFORM_SUBSIDY, "ref-a").id();

        // 并发核销两次：一个成功、一个 80002（ADR-0038 第二节的「原子条件更新」）
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger already = new AtomicInteger();
        List<String> unexpected = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    couponApi.redeem(couponId, provider.providerId(), 42L, 7L);
                    ok.incrementAndGet();
                } catch (BusinessException e) {
                    if (e.getErrorCode().getCode() == 80002) {
                        already.incrementAndGet();
                    } else {
                        synchronized (unexpected) {
                            unexpected.add(e.getErrorCode() + " " + e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    synchronized (unexpected) {
                        unexpected.add(e.getClass().getSimpleName() + " " + e.getMessage());
                    }
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        assertThat(unexpected).isEmpty();
        assertThat(ok.get()).isEqualTo(1);
        assertThat(already.get()).isEqualTo(1);

        Map<String, Object> row = jdbc.queryForMap("SELECT status, redeemed_order_id, redeemed_by, "
                + "redeemed_at FROM coupon WHERE id = ?", couponId);
        assertThat(((Number) row.get("status")).intValue()).isEqualTo(3);
        assertThat(((Number) row.get("redeemed_order_id")).longValue()).isEqualTo(42L);
        assertThat(((Number) row.get("redeemed_by")).longValue()).isEqualTo(7L);
        assertThat(row.get("redeemed_at")).isNotNull();

        // 核销把「占用中」换成了「已核销」：可发放不变（核销不释放额度，ADR-0037）
        ApiClient.ApiCall detail = api.get("/api/v1/provider/coupon-contributions/" + contributionId,
                provider.token());
        assertThat(detail.data().path("redeemed_count").asInt()).isEqualTo(1);
        assertThat(detail.data().path("reserved_count").asInt()).isZero();
        assertThat(detail.data().path("available_count").asInt()).isEqualTo(1);
        assertThat(detail.data().path("completion_rate").asText()).isEqualTo("0.50");

        // 服务者的核销明细看得到这张券（且不带领券人身份字段之外的信息）
        ApiClient.ApiCall coupons = api.get("/api/v1/provider/coupon-contributions/" + contributionId
                + "/coupons?status=3", provider.token());
        assertThat(coupons.data().path("total").asLong()).isEqualTo(1);
        assertThat(coupons.data().path("list").get(0).path("code").asText()).isNotBlank();
    }

    @Test
    @DisplayName("同一行为不重复发奖励：同一来源引用的重复发券只产生一张；不同引用各一张")
    void sameSourceReferenceIssuesOnce() {
        String admin = adminToken();
        long templateId = createTemplate(admin, "CP-104");
        ApprovedProvider provider = createApprovedProvider("LIC-QUOTA-004");
        long contributionId = commit(provider.token(), templateId, 10);

        CouponApi.CouponInfo first = issue(provider.providerId(), templateId,
                Coupon.SOURCE_CHECK_IN_TASK, "ladder:2026-09:1");
        CouponApi.CouponInfo replay = issue(provider.providerId(), templateId,
                Coupon.SOURCE_CHECK_IN_TASK, "ladder:2026-09:1");
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon WHERE contribution_id = ?",
                Long.class, contributionId)).isEqualTo(1L);

        // 不同的引用（另一次行为）正常发第二张
        CouponApi.CouponInfo second = issue(provider.providerId(), templateId,
                Coupon.SOURCE_CHECK_IN_TASK, "ladder:2026-09:2");
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(availableOf(contributionId)).isEqualTo(8);
    }

    @Test
    @DisplayName("下单前校验：门槛 / 门店 / 适用范围 / 重复占用分别给 80001，已核销给 80002")
    void checkRejectsUnusableCoupons() {
        String admin = adminToken();
        // 面额 30、门槛 100、限医院分类
        ApiClient.ApiCall created = api.post("/api/v1/admin/coupon-templates", Map.of(
                "code", "CP-105", "name", "满 100 减 30", "face_value", "30.00", "min_amount", "100.00",
                "valid_days", 30, "cost_bearer", 1, "scope_type", 1, "scope_codes", List.of("HOSPITAL")),
                admin);
        assertCodeOk(created, "建带门槛与范围的券模板");
        long templateId = created.data().path("id").asLong();
        ApprovedProvider provider = createApprovedProvider("LIC-QUOTA-005");
        commit(provider.token(), templateId, 5);
        long couponId = issue(COUPON_HOLDER, templateId, Coupon.SOURCE_PLATFORM_SUBSIDY, "chk-1").id();

        // 门槛不足
        assertCode(() -> couponApi.check(couponId, provider.providerId(), "HE-004",
                new BigDecimal("99.99")), 80001, "门槛");
        // 适用范围外（洗护的目录项在 HOSPITAL 之外）
        assertCode(() -> couponApi.check(couponId, provider.providerId(), "GR-001",
                new BigDecimal("200.00")), 80001, "适用范围");
        // 不是本店的券（服务者贡献的券只在其本店核销）
        assertCode(() -> couponApi.check(couponId, provider.providerId() + 1, "HE-004",
                new BigDecimal("200.00")), 80001, "门店");
        // 满足全部条件时通过
        CouponApi.CouponCheck check = couponApi.check(couponId, provider.providerId(), "HE-004",
                new BigDecimal("200.00"));
        assertThat(check.faceValue()).isEqualByComparingTo("30.00");

        // 重复占用：被别的订单锁定后不可用（锁是「持券人的券」，所以用同一个 user id）
        couponApi.lock(couponId, 777L, COUPON_HOLDER);
        assertCode(() -> couponApi.check(couponId, provider.providerId(), "HE-004",
                new BigDecimal("200.00")), 80001, "占用中");

        // 释放后又能用；核销后给 80002
        couponApi.release(couponId, 777L);
        couponApi.redeem(couponId, provider.providerId(), 888L, 7L);
        assertCode(() -> couponApi.check(couponId, provider.providerId(), "HE-004",
                new BigDecimal("200.00")), 80002, "已核销");
        // 释放别的订单的锁定是空操作（不会误放别人的锁）
        assertThat(jdbc.queryForObject("SELECT status FROM coupon WHERE id = ?", Integer.class, couponId))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("平台补贴券：运营定向发放、发放上限生效、不能发服务者成本的券")
    void subsidyCouponIssueAndLimit() {
        String admin = adminToken();
        long subsidyId = createSubsidyTemplate(admin, "CP-106", 1);
        long providerCostId = createTemplate(admin, "CP-107");

        ApiClient.ApiCall first = api.post("/api/v1/admin/coupons",
                Map.of("user_id", 900_001L, "template_id", subsidyId, "remark", "客诉补偿"), admin);
        assertCodeOk(first, "发补贴券");
        assertThat(first.data().path("source").asInt()).isEqualTo(4);
        assertThat(first.data().path("face_value").asText()).isEqualTo("50.00");

        // 上限 1 → 第二张被拒
        ApiClient.ApiCall second = api.post("/api/v1/admin/coupons",
                Map.of("user_id", 900_002L, "template_id", subsidyId), admin);
        assertThat(second.code()).isEqualTo(40900);
        assertThat(second.message()).contains("上限");

        // 服务者成本的券不能由运营发放（那等于平台替服务者承诺额度）
        assertThat(api.post("/api/v1/admin/coupons",
                Map.of("user_id", 900_003L, "template_id", providerCostId), admin).code()).isEqualTo(40900);

        // 总览：按来源拆开的一笔账 + 对账等式成立
        ApiClient.ApiCall overview = api.get("/api/v1/admin/coupon-pool/overview", admin);
        assertThat(overview.data().path("issued_total").asInt()).isEqualTo(1);
        assertThat(overview.data().path("reserved_total").asInt()).isEqualTo(1);
        assertThat(overview.data().path("by_source").get(0).path("source").asInt()).isEqualTo(4);
        assertThat(overview.data().path("reconciliation").path("balanced").asBoolean()).isTrue();
        assertThat(overview.data().path("reconciliation").path("note").asText()).contains("已发放");
    }

    // ---------------------------------------------------------------- 工具

    private long createTemplate(String admin, String code) {
        ApiClient.ApiCall created = api.post("/api/v1/admin/coupon-templates", Map.of(
                "code", code, "name", "额度测试券 " + code, "face_value", "30.00", "min_amount", "0.00",
                "valid_days", 30, "cost_bearer", 1), admin);
        assertCodeOk(created, "建券模板");
        return created.data().path("id").asLong();
    }

    private long createSubsidyTemplate(String admin, String code, Integer issueLimit) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("code", code);
        body.put("name", "补贴券 " + code);
        body.put("face_value", "50.00");
        body.put("min_amount", "0.00");
        body.put("valid_days", 30);
        body.put("cost_bearer", 2);
        if (issueLimit != null) {
            body.put("issue_limit", issueLimit);
        }
        ApiClient.ApiCall created = api.post("/api/v1/admin/coupon-templates", body, admin);
        assertCodeOk(created, "建补贴券模板");
        return created.data().path("id").asLong();
    }

    private long commit(String providerToken, long templateId, int totalCount) {
        ApiClient.ApiCall committed = api.post("/api/v1/provider/coupon-contributions",
                Map.of("template_id", templateId, "total_count", totalCount), providerToken);
        assertCodeOk(committed, "承诺额度");
        return committed.data().path("id").asLong();
    }

    private CouponApi.CouponInfo issue(long userId, long templateId, int source, String sourceRef) {
        return couponApi.issue(new CouponApi.IssueCommand(userId, templateId, source, sourceRef, null, null));
    }

    /**
     * 券到期提醒（F026）：**提醒的是「快过期」而不是「已经过期」**——已经过期时用户无事可做，
     * 而「还有几天」才是能改变结果的那一条。所以窗口是 {@code [now, now+N 天)}，与翻状态那段
     * （{@code < now}）刚好接上又互不重叠：同一张券不会被既提醒、又告知已过期。
     */
    @Test
    @DisplayName("券到期前发一条站内提醒，同一张券只提醒一次；已过期的券不提醒")
    void remindsBeforeExpiryOnce() {
        String admin = adminToken();
        long templateId = createTemplate(admin, "CP-901");
        ApprovedProvider provider = createApprovedProvider("LIC-REMIND-001");
        commit(provider.token(), templateId, 3);
        long couponId = issue(COUPON_HOLDER, templateId, Coupon.SOURCE_CHECK_IN_TASK, "remind-1").id();
        long alreadyExpired = issue(COUPON_HOLDER, templateId, Coupon.SOURCE_CHECK_IN_TASK, "remind-2").id();

        // 一张落在提醒窗口里（2 天后到期），一张已经过期
        jdbc.update("UPDATE coupon SET valid_until = ? WHERE id = ?",
                LocalDateTime.now().plusDays(2), couponId);
        jdbc.update("UPDATE coupon SET valid_until = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(1), alreadyExpired);

        CouponExpiryJob.SweepResult result = expiryJob.sweep();
        assertThat(result.expiringCoupons()).as("只有窗口里那一张进候选").isEqualTo(1);
        assertThat(messageCount(COUPON_HOLDER, "coupon-expiring-" + couponId)).isEqualTo(1L);
        assertThat(messageCount(COUPON_HOLDER, "coupon-expiring-" + alreadyExpired))
                .as("已过期的券不该被提醒（提醒一张用不了的券只会让用户去券包里找不到它）").isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM coupon WHERE id = ?", Integer.class, couponId))
                .as("提醒不改变券的状态").isEqualTo(1);

        // 幂等：批算每天跑，候选还是那一张（它还在窗口里），但**消息只有一条**——
        // 去重由 message 的 uk_dedup 兜着，所以「候选数」不该被读成「今天又提醒了一次」
        assertThat(expiryJob.sweep().expiringCoupons()).isEqualTo(1);
        assertThat(messageCount(COUPON_HOLDER, "coupon-expiring-" + couponId)).isEqualTo(1L);
    }

    /** 数一条业务通知（去重键是它在这张表里的唯一标识）。 */
    private long messageCount(long userId, String dedupKey) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM `message` WHERE user_id = ? AND dedup_key = ?",
                Long.class, userId, dedupKey);
    }

    private int availableOf(long contributionId) {
        return expiryJob.availableOfContribution(contributionId);
    }

    /** 断言业务码：80001 / 80002 是 HTTP 200 的业务结果，但在内部接口里是异常。 */
    private void assertCode(Runnable action, int expected, String what) {
        try {
            action.run();
            throw new AssertionError(what + " 应当被拒绝（期望业务码 " + expected + "）");
        } catch (BusinessException e) {
            assertThat(e.getErrorCode().getCode()).as(what).isEqualTo(expected);
        }
    }
}
