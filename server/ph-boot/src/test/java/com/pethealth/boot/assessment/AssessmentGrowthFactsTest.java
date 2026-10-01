package com.pethealth.boot.assessment;

import com.fasterxml.jackson.databind.JsonNode;
import com.pethealth.provider.service.AssessmentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 考核的**拉新与券两项真实取数**（一期验收标准的「服务者考核机制」的最后一块拼图）。
 *
 * <p>与 {@code AssessmentStubSupport} 里那批算分用例的分工：那些用例用 stub 精确摆组合、验算法；
 * 这个类**不导入 stub**，走的是 {@code ph-privilege} 的真实现
 * （{@code ProviderGrowthFactsService}），验的是「数从哪来、口径对不对」：
 *
 * <ul>
 *   <li><b>拉新</b>数的是「结算为有效、且结算时间落在账期内」的门店归因关系
 *       （{@code invite_relation.inviter_provider_id}，V44）。观察窗里与判无效的那两条**不算**；
 *   <li><b>券</b>的达成量 = 贡献完成率 × 本账期核销数。核销数只认「核销门店是本店、且核销时间在账期内」
 *       的券——别人的店核销的、或者上个月核销的，都不该算进这一期；
 *   <li>两项都参与时，总分按 40/40/20 加权（没有缺项，权重不用重算）。
 * </ul>
 *
 * <p>这张网的算法本身在 {@code AssessmentScoringTest} 里已经逐条钉过，这里只验「接上真数之后
 * 算出来的是不是那个人工对过账的数」——所以断言里把中间量（原始值、达标线）也写出来，
 * 数字不对时能一眼看出是哪一段错的。
 */
class AssessmentGrowthFactsTest extends AssessmentTestSupport {

    @Autowired
    private AssessmentService assessmentService;

    @Test
    @DisplayName("拉新与券都取到真数：加权总分按 40/40/20 算出来")
    void realFactsFeedBothDimensions() {
        ApprovedProvider provider = createApprovedProvider("LIC-GROWTH-001");
        long providerId = provider.providerId();

        // 平台给了拉新入口（推广码）+ 达标线 5 人；券池里有一张可贡献的服务者成本券
        jdbc.update("INSERT INTO `provider_invite_code` (`provider_id`, `code`) VALUES (?, ?)",
                providerId, "PVTESTGROW");
        jdbc.update("UPDATE `assessment_rule` SET `invite_weight` = 40, `coupon_weight` = 40, "
                + "`process_weight` = 20, `invite_target` = 5, `coupon_target` = 10.00, "
                + "`response_minutes_target` = 30 WHERE `id` = 1");

        // 门店归因：2 条有效（结算在本账期内）、1 条还在观察窗、1 条判无效
        seedProviderRelation(providerId, 2, "2026-08-05 10:00:00");
        seedProviderRelation(providerId, 2, "2026-08-20 09:30:00");
        seedProviderRelation(providerId, 1, null);
        seedProviderRelation(providerId, 3, "2026-08-06 10:00:00");

        // 券：承诺 10 张，**累计**核销 5 张（本账期 4 张 + 上月 1 张）
        // → 完成率 0.50（累计口径，见 ProviderGrowthFactsService 的说明）
        // → 达成量 = 完成率 × **本账期**核销数 = 0.50 × 4 = 2.00
        long templateId = seedProviderCostTemplate();
        long contributionId = seedContribution(providerId, templateId, 10);
        for (int i = 0; i < 4; i++) {
            seedRedeemedCoupon(contributionId, providerId, templateId, "2026-08-1" + (i + 1) + " 12:00:00");
        }
        // 干扰项：上月核销的一张。它进**完成率**（累计），但不进本账期的核销数——
        // 这两个数一个累计、一个当期，考核把它们乘起来才是那条口径。
        seedRedeemedCoupon(contributionId, providerId, templateId, "2026-07-30 12:00:00");

        // 过程分：3 单全部已完成（核销率 1、报工完整率 1、取消率 0、响应达标 → 100.00）
        seedOrder(providerId, 3, null, 10, LocalDate.of(2026, 8, 3));
        seedOrder(providerId, 3, null, 10, LocalDate.of(2026, 8, 4));
        seedOrder(providerId, 3, null, 10, LocalDate.of(2026, 8, 5));

        assertThat(assessmentService.calculate(providerId, "2026-08")).isTrue();

        JsonNode detail = api.get("/api/v1/provider/assessments/2026-08", provider.token()).data();
        JsonNode invite = item(detail, "INVITE");
        assertThat(invite.path("participated").asBoolean()).isTrue();
        assertThat(invite.path("raw_value").asText()).isEqualTo("有效邀请 2 人");
        assertThat(invite.path("score").asText()).isEqualTo("40.00");

        JsonNode coupon = item(detail, "COUPON");
        assertThat(coupon.path("participated").asBoolean()).isTrue();
        assertThat(coupon.path("raw_value").asText()).contains("完成率 0.50").contains("核销 4 张");
        assertThat(coupon.path("score").asText()).isEqualTo("20.00");

        JsonNode process = item(detail, "PROCESS");
        assertThat(process.path("score").asText()).isEqualTo("100.00");

        // 三项都参与 → 权重不被重算；总分 = (40×40 + 20×40 + 100×20) / 100 = 44.00
        assertThat(detail.path("participated_weight").asInt()).isEqualTo(100);
        assertThat(detail.path("total_score").asText()).isEqualTo("44.00");
    }

    @Test
    @DisplayName("有入口但一条有效邀请都没有 → 记 0 分（该做而没做），不是「未参与」")
    void entryWithoutEffectiveInvitesScoresZero() {
        ApprovedProvider provider = createApprovedProvider("LIC-GROWTH-002");
        jdbc.update("INSERT INTO `provider_invite_code` (`provider_id`, `code`) VALUES (?, ?)",
                provider.providerId(), "PVTESTZERO");
        jdbc.update("UPDATE `assessment_rule` SET `invite_target` = 5 WHERE `id` = 1");
        seedOrder(provider.providerId(), 1, null, 10, LocalDate.of(2026, 8, 3));

        assertThat(assessmentService.calculate(provider.providerId(), "2026-08")).isTrue();

        JsonNode invite = item(api.get("/api/v1/provider/assessments/2026-08", provider.token()).data(), "INVITE");
        assertThat(invite.path("participated").asBoolean()).isTrue();
        assertThat(invite.path("score").asText()).isEqualTo("0.00");
        assertThat(invite.path("note").asText()).contains("该做而没做");
    }

    // ---------------------------------------------------------------- 造数（直接写库：算分用例要的是确定的行，不是走一遍业务流程）

    private void seedProviderRelation(long providerId, int status, String settledAt) {
        // 门店归因的 inviter_user_id 为 NULL（V44 的 CHECK 要求两者恰好有一个）
        jdbc.update("INSERT INTO `invite_relation` (`inviter_user_id`, `inviter_provider_id`, `invitee_user_id`, "
                        + "`invite_code`, `channel`, `status`, `attributed_at`, `settled_at`) "
                        + "VALUES (NULL, ?, ?, ?, 1, ?, '2026-08-01 09:00:00', ?)",
                providerId, nextInviteeUserId(), "PVTESTGROW", status,
                settledAt == null ? null : LocalDateTime.parse(settledAt.replace(' ', 'T')));
    }

    /** 造一个不撞的「被邀请人」id：本用例不建账号，只借那个唯一键占位。 */
    private static final java.util.concurrent.atomic.AtomicLong INVITEE_SEQ =
            new java.util.concurrent.atomic.AtomicLong(900_000_000L);

    private static long nextInviteeUserId() {
        return INVITEE_SEQ.incrementAndGet();
    }

    private long seedProviderCostTemplate() {
        jdbc.update("INSERT INTO `coupon_template` (`code`, `name`, `face_value`, `min_amount`, `valid_days`, "
                        + "`cost_bearer`, `scope_type`, `scope_codes`, `status`) "
                        + "VALUES ('TEST-PROVIDER-FEE', '测试服务者成本券', 20.00, 0.00, 30, 1, 0, '', 1)");
        return jdbc.queryForObject("SELECT `id` FROM `coupon_template` WHERE `code` = 'TEST-PROVIDER-FEE'", Long.class);
    }

    private long seedContribution(long providerId, long templateId, int totalCount) {
        jdbc.update("INSERT INTO `coupon_contribution` (`provider_id`, `template_id`, `total_count`, `status`) "
                + "VALUES (?, ?, ?, 1)", providerId, templateId, totalCount);
        return jdbc.queryForObject("SELECT `id` FROM `coupon_contribution` WHERE `provider_id` = ? ORDER BY `id` DESC LIMIT 1",
                Long.class, providerId);
    }

    private void seedRedeemedCoupon(long contributionId, long providerId, long templateId, String redeemedAt) {
        jdbc.update("INSERT INTO `coupon` (`code`, `user_id`, `template_id`, `source`, `source_ref`, "
                        + "`contribution_id`, `provider_id`, `face_value`, `min_amount`, `scope_type`, `scope_codes`, "
                        + "`status`, `valid_from`, `valid_until`, `issued_at`, `redeemed_at`, `redeemed_by`) "
                        + "VALUES (?, ?, ?, 1, ?, ?, ?, 20.00, 0.00, 0, '', 3, "
                        + "'2026-06-01 00:00:00', '2026-12-31 00:00:00', '2026-06-01 00:00:00', ?, 1)",
                "C" + nextInviteeUserId(), nextInviteeUserId(), templateId, "seed-" + nextInviteeUserId(),
                contributionId, providerId, LocalDateTime.parse(redeemedAt.replace(' ', 'T')));
    }
}
