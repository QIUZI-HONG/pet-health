package com.pethealth.boot.provider;

import com.pethealth.boot.support.ApiClient;
import com.pethealth.provider.service.QualificationExpiryJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 资质到期的软处理（2026-09-29 项目所有者口径，ADR-0035）：
 * **过期后自动下架该服务者的全部服务项，但不注销服务者**；补交材料后即可恢复上架。
 *
 * <p><b>这一处刻意偏离了「只测 HTTP 缝」（ADR-0014）。</b> 批算没有 HTTP 入口——
 * 它是 {@code @Scheduled} 任务，唯一的触发方式是时间。与其让它完全没有测试
 * （「自动下架」这条规则写错了也没人知道），不如直接调它的 {@code sweep()}：
 * 断言仍然只看**外部可观察状态**（库里的服务项状态、响应里的状态、审核流水），
 * 不碰任何内部实现。等将来有运营侧的「手动触发批算」入口，这个用例可以改回走 HTTP。
 */
class QualificationExpiryTest extends ProviderApiTestSupport {

    @Autowired
    private QualificationExpiryJob expiryJob;

    @Test
    @DisplayName("资质全部过期：批算把在架服务项全部下架，并留下系统流水（不注销服务者）")
    void expiredQualificationDelistsAllListings() {
        String admin = adminToken();
        ApprovedProvider provider = createApprovedProvider("LIC-EXPIRY-001");
        long listingA = createListing(provider.token(), "HE-004", "150.00").data().path("id").asLong();
        long listingB = createListing(provider.token(), "HE-005", "320.00").data().path("id").asLong();
        long listingC = createListing(provider.token(), "HE-010", "150.00").data().path("id").asLong();
        assertCodeOk(api.post("/api/v1/admin/service-listings/" + listingA + "/approve", Map.of(), admin),
                "通过 A");
        assertCodeOk(api.post("/api/v1/admin/service-listings/" + listingB + "/approve", Map.of(), admin),
                "通过 B");
        // C 留在待审核：它不是「在架」，批算不该动它
        assertThat(api.get("/api/v1/provider/services", provider.token()).data().path("total").asLong())
                .isEqualTo(3);

        // 把资质改成昨天到期（模拟时间流逝——批算看的是库里那一列，不看提交时间）
        jdbc.update("UPDATE provider_qualification SET valid_until = ? WHERE provider_id = ?",
                LocalDate.now().minusDays(1), provider.providerId());

        QualificationExpiryJob.SweepResult result = expiryJob.sweep();
        assertThat(result.delistedProviders()).isEqualTo(1);
        assertThat(result.delistedListings()).isEqualTo(2);

        // 在架的两个都被下架；待审核的那个不受影响（它本来也没在架）
        assertThat(statusOf(listingA)).isEqualTo(2);
        assertThat(statusOf(listingB)).isEqualTo(2);
        assertThat(statusOf(listingC)).isZero();

        // 服务者**没有被注销**：状态仍是正常，历史与客户关系都留着
        assertThat(jdbc.queryForObject("SELECT status FROM provider WHERE id = ?", Integer.class,
                provider.providerId())).isEqualTo(1);

        // 系统写入的流水：谁（0=系统）、何时、结论（系统自动下架）
        Map<String, Object> log = jdbc.queryForMap(
                "SELECT actor_id, actor_domain, action, remark FROM provider_review_log "
                        + "WHERE target_type = 2 AND target_id = ? ORDER BY id DESC LIMIT 1", listingB);
        assertThat(((Number) log.get("actor_id")).longValue()).isZero();
        assertThat(log.get("actor_domain")).isEqualTo("");
        assertThat(((Number) log.get("action")).intValue()).isEqualTo(6);
        assertThat(log.get("remark")).asString().contains("资质已过期");

        // 过期状态下来新增选品也被拦（否则会堆出一堆永远上不了架的项）
        ApiClient.ApiCall blocked = createListing(provider.token(), "HE-012", "200.00");
        assertThat(blocked.code()).isEqualTo(40300);
        assertThat(blocked.message()).contains("资质");

        // 更早的用例会重复跑批算：第二次不该再产生下架（幂等）
        QualificationExpiryJob.SweepResult second = expiryJob.sweep();
        assertThat(second.delistedProviders()).isZero();
        assertThat(second.delistedListings()).isZero();
    }

    @Test
    @DisplayName("补交材料后恢复上架：新材料只要没过期，上架门禁就放开")
    void resubmittedQualificationRestoresListing() {
        String admin = adminToken();
        ApprovedProvider provider = createApprovedProvider("LIC-EXPIRY-002");
        long listingId = createListing(provider.token(), "HE-004", "150.00").data().path("id").asLong();
        assertCodeOk(api.post("/api/v1/admin/service-listings/" + listingId + "/approve", Map.of(), admin),
                "先让它上架");
        jdbc.update("UPDATE provider_qualification SET valid_until = ? WHERE provider_id = ?",
                LocalDate.now().minusDays(30), provider.providerId());
        expiryJob.sweep();
        assertThat(statusOf(listingId)).isEqualTo(2);

        // 过期时上架被拦
        ApiClient.ApiCall blocked = api.put("/api/v1/provider/services/" + listingId + "/status",
                Map.of("status", 1), provider.token());
        assertThat(blocked.code()).isEqualTo(40300);
        assertThat(blocked.message()).contains("资质已过期");

        // 补交一份新的（有效期到明年）→ 上架立刻恢复，不必等运营复核
        ApiClient.ApiCall resubmitted = api.put("/api/v1/provider/profile/qualifications",
                Map.of("qualifications", List.of(Map.of(
                        "type", 1,
                        "name", "营业执照（续期）",
                        "cert_no", "LIC-EXPIRY-002-NEW",
                        "valid_from", LocalDate.now().toString(),
                        "valid_until", LocalDate.now().plusYears(1).toString()))), provider.token());
        assertCodeOk(resubmitted, "补交材料");
        assertThat(resubmitted.data().path("business_hours")).isEmpty();

        ApiClient.ApiCall relisted = api.put("/api/v1/provider/services/" + listingId + "/status",
                Map.of("status", 1), provider.token());
        assertCodeOk(relisted, "恢复上架");
        assertThat(relisted.data().path("status").asInt()).isEqualTo(1);

        // 补交的材料是「待审核」，且旧的过期材料已被换掉（逻辑删除，不再出现在响应里）
        assertThat(resubmittedQualificationCount(provider.providerId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM provider_qualification "
                + "WHERE provider_id = ? AND is_deleted = 0", Integer.class, provider.providerId())).isZero();
    }

    @Test
    @DisplayName("只要还有一份未过期的资质，批算就不下架（多种材料是常态）")
    void oneValidQualificationIsEnough() {
        ApprovedProvider provider = createApprovedProvider("LIC-EXPIRY-003");
        long listingId = createListing(provider.token(), "HE-004", "150.00").data().path("id").asLong();

        // 再加一份长期有效的材料（到期日为空 = 长期有效），然后把第一份改成过期
        assertCodeOk(api.put("/api/v1/provider/profile/qualifications",
                Map.of("qualifications", List.of(
                        Map.of("type", 1, "name", "营业执照", "cert_no", "LIC-EXPIRY-003-A",
                                "valid_until", LocalDate.now().minusDays(1).toString()),
                        Map.of("type", 2, "name", "执业许可证", "cert_no", "LIC-EXPIRY-003-B"))),
                provider.token()), "补交材料");

        QualificationExpiryJob.SweepResult result = expiryJob.sweep();
        assertThat(result.delistedProviders()).isZero();
        assertThat(statusOf(listingId)).isZero();
    }

    @Test
    @DisplayName("到期前 30 天进入提醒窗口（本切片只记日志，投递待对接消息中心）")
    void expiringSoonIsReported() {
        ApprovedProvider provider = createApprovedProvider("LIC-EXPIRY-004");
        jdbc.update("UPDATE provider_qualification SET valid_until = ? WHERE provider_id = ?",
                LocalDate.now().plusDays(QualificationExpiryJob.RENEWAL_REMINDER_DAYS - 1),
                provider.providerId());

        QualificationExpiryJob.SweepResult result = expiryJob.sweep();
        assertThat(result.expiringProviders()).isEqualTo(1);
        assertThat(result.delistedProviders()).isZero();
        // 还没过期，服务项照常
        assertCodeOk(api.get("/api/v1/provider/profile", provider.token()), "读门店");
    }

    private int statusOf(long listingId) {
        Integer status = jdbc.queryForObject("SELECT status FROM provider_service WHERE id = ?",
                Integer.class, listingId);
        return status == null ? -1 : status;
    }

    private long resubmittedQualificationCount(long providerId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM provider_qualification WHERE provider_id = ? AND is_deleted = 0",
                Long.class, providerId);
    }
}
