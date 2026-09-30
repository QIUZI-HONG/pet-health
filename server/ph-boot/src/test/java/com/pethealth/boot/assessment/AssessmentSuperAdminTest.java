package com.pethealth.boot.assessment;

import com.fasterxml.jackson.databind.JsonNode;
import com.pethealth.boot.support.ApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单项分覆盖与规则配置（**只归超级管理员**，ADR-0037 第一节的矩阵）。
 *
 * <p>这个上下文**配了超管名单**（{@code app.assessment.super-admin-user-ids=900001}），
 * 对应 ph-boot 的 {@code AssessmentPermissionTest} 里那一组 fail-closed 用例。
 * 名单里那个固定 id 的账号由 {@link AssessmentTestSupport#createSuperAdminAccount()} 插进 user 表
 * （超管名单是进程启动时读的环境变量，测试里的自增账号 id 对不上，只能用固定 id）。
 *
 * <p>ADR-0039 第三节那一句「允许超级管理员覆盖单项分，但必须留痕：谁、何时、理由、覆盖前后值都留。
 * 否则算法是黑箱，服务者无法申诉」在这一组用例里逐项落地：
 * 覆盖后**服务者侧也能看到留痕**（最后一条用例），这是「可申诉」的前提。
 */
@TestPropertySource(properties = "app.assessment.super-admin-user-ids=900001")
class AssessmentSuperAdminTest extends AssessmentStubSupport {

    @Test
    @DisplayName("覆盖单项分：留痕（前后值 / 理由 / 操作者）+ 总分与等级重算 + 写回门店")
    void overrideKeepsTrailAndRecalculates() {
        ApprovedProvider provider = createApprovedProvider("LIC-SUPER-001");
        configureRule(4, "10.00", 30);
        stub().setInvites(2);
        stub().setContributableTemplates(1);
        stub().setCoupon(8, "0.80");
        seedOrder(provider.providerId(), 3, null, 20, day(3));
        seedOrder(provider.providerId(), 3, null, 20, day(4));
        seedOrder(provider.providerId(), 3, null, 20, day(5));
        seedOrder(provider.providerId(), 2, null, 20, day(6));
        assessmentService.calculate(provider.providerId(), PERIOD.toString());
        long scoreId = scoreIdOf(provider.token());
        // 覆盖前：拉新 50 / 券 64 / 过程 93.75 → 总分 64.35
        assertThat(totalScoreOf(provider.providerId(), PERIOD)).isEqualByComparingTo("64.35");

        ApiClient.ApiCall overridden = api.post("/api/v1/admin/assessments/" + scoreId + "/overrides",
                Map.of("item_code", "PROCESS", "score", "100.00",
                        "reason", "门店申诉：当月系统故障导致漏报工，运营核实后调整"),
                superAdminToken());
        assertCodeOk(overridden, "覆盖过程分");
        JsonNode data = overridden.data();

        // 总分按参与项权重重算：(50×40 + 64×40 + 100×20) / 100 = 65.60
        assertThat(data.path("total_score").asText()).isEqualTo("65.60");
        assertThat(data.path("overridden").asBoolean()).isTrue();
        JsonNode process = item(data, "PROCESS");
        assertThat(process.path("score").asText()).isEqualTo("100.00");
        assertThat(process.path("calculated_score").asText()).isEqualTo("93.75");  // 算法原值不动
        assertThat(process.path("overridden").asBoolean()).isTrue();
        assertThat(process.path("note").asText()).contains("被超级管理员覆盖");

        // 留痕：谁（超管账号 id）、改成多少、为什么
        assertThat(data.path("overrides")).hasSize(1);
        JsonNode log = data.path("overrides").get(0);
        assertThat(log.path("item_code").asText()).isEqualTo("PROCESS");
        assertThat(log.path("before_score").asText()).isEqualTo("93.75");
        assertThat(log.path("after_score").asText()).isEqualTo("100.00");
        assertThat(log.path("reason").asText()).contains("系统故障");
        assertThat(log.path("operator_id").asLong()).isEqualTo(SUPER_ADMIN_ID);

        // 库里两处都对得上：分表的总分与门店的最近一期分（推荐侧读的是后者）
        assertThat(totalScoreOf(provider.providerId(), PERIOD)).isEqualByComparingTo("65.60");
        assertThat(jdbc.queryForObject("SELECT `monthly_score` FROM `provider` WHERE `id` = ?",
                BigDecimal.class, provider.providerId())).isEqualByComparingTo("65.60");
        assertThat(jdbc.queryForObject("SELECT `overridden` FROM `assessment_monthly_score` "
                + "WHERE `id` = ?", Integer.class, scoreId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT `score` FROM `assessment_item_score` "
                + "WHERE `score_id` = ? AND `item_code` = 'PROCESS'", BigDecimal.class, scoreId))
                .isEqualByComparingTo("100.00");

        // 再次覆盖：追加一条（before 是上一次的 after），原值仍在 calculated_score 上
        ApiClient.ApiCall again = api.post("/api/v1/admin/assessments/" + scoreId + "/overrides",
                Map.of("item_code", "PROCESS", "score", "10.00", "reason", "复核后撤回上一次调整"),
                superAdminToken());
        assertCodeOk(again, "第二次覆盖");
        assertThat(again.data().path("overrides")).hasSize(2);
        JsonNode second = again.data().path("overrides").get(1);
        assertThat(second.path("before_score").asText()).isEqualTo("100.00");
        assertThat(second.path("after_score").asText()).isEqualTo("10.00");
        // 总分再算：(50×40 + 64×40 + 10×20) / 100 = 47.60
        assertThat(totalScoreOf(provider.providerId(), PERIOD)).isEqualByComparingTo("47.60");
    }

    @Test
    @DisplayName("留痕对服务者可见（否则无法申诉）：服务者侧的明细里带着覆盖记录")
    void overrideTrailIsVisibleToProvider() {
        ApprovedProvider provider = createApprovedProvider("LIC-SUPER-002");
        configureRule(0, "0.00", 30);
        seedOrder(provider.providerId(), 3, null, 10, day(3));
        assessmentService.calculate(provider.providerId(), PERIOD.toString());
        long scoreId = scoreIdOf(provider.token());
        assertCodeOk(api.post("/api/v1/admin/assessments/" + scoreId + "/overrides",
                Map.of("item_code", "PROCESS", "score", "60.00", "reason", "门店申诉：有一单重复计了取消"),
                superAdminToken()), "覆盖过程分");

        JsonNode mine = api.get("/api/v1/provider/assessments/" + PERIOD, provider.token()).data();
        assertThat(mine.path("overrides")).hasSize(1);
        assertThat(mine.path("overrides").get(0).path("reason").asText()).contains("重复计了取消");
        assertThat(mine.path("overrides").get(0).path("before_score").asText()).isEqualTo("100.00");
        assertThat(mine.path("overrides").get(0).path("after_score").asText()).isEqualTo("60.00");
        assertThat(mine.path("total_score").asText()).isEqualTo("60.00");
        // 覆盖撤回（服务者不该只看到「有人改过」而看不到结果）
        assertThat(item(mine, "PROCESS").path("score").asText()).isEqualTo("60.00");
    }

    @Test
    @DisplayName("覆盖的边界：只能覆盖三项主项、理由必填、分数越界与不存在的记录都拒绝")
    void overrideBoundaries() {
        ApprovedProvider provider = createApprovedProvider("LIC-SUPER-003");
        configureRule(0, "0.00", 30);
        seedOrder(provider.providerId(), 3, null, 10, day(3));
        assessmentService.calculate(provider.providerId(), PERIOD.toString());
        long scoreId = scoreIdOf(provider.token());
        String token = superAdminToken();

        // 过程子项不可覆盖（它们是过程分的构成，覆盖等于绕过算法）
        ApiClient.ApiCall child = api.post("/api/v1/admin/assessments/" + scoreId + "/overrides",
                Map.of("item_code", "PROCESS_RESPONSE", "score", "100.00", "reason", "测试"), token);
        assertThat(child.code()).isEqualTo(40001);
        assertThat(child.message()).contains("三项主项");

        // 理由必填
        ApiClient.ApiCall noReason = api.post("/api/v1/admin/assessments/" + scoreId + "/overrides",
                Map.of("item_code", "PROCESS", "score", "100.00"), token);
        assertThat(noReason.code()).isEqualTo(40001);

        // 分数越界
        ApiClient.ApiCall tooBig = api.post("/api/v1/admin/assessments/" + scoreId + "/overrides",
                Map.of("item_code", "PROCESS", "score", "120.00", "reason", "测试"), token);
        assertThat(tooBig.code()).isEqualTo(40001);

        // 不存在的考核记录
        ApiClient.ApiCall missing = api.post("/api/v1/admin/assessments/99999999/overrides",
                Map.of("item_code", "PROCESS", "score", "50.00", "reason", "测试"), token);
        assertThat(missing.code()).isEqualTo(40400);

        // 一次都没成功 → 一条留痕都没有
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `assessment_override_log`", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("规则配置：改完立刻生效、只影响之后算出的账期；非法规则一律 40001")
    void updateRuleIsValidatedAndTakesEffect() {
        String token = superAdminToken();
        ApiClient.ApiCall before = api.get("/api/v1/admin/assessments/rules", token);
        assertCodeOk(before, "读规则");
        assertThat(before.data().path("invite_weight").asInt()).isEqualTo(40);
        assertThat(before.data().path("levels")).hasSize(3);

        // 权重之和必须是 100
        Map<String, Object> badWeights = new java.util.HashMap<>(ruleBody("10.00"));
        badWeights.put("invite_weight", 30);
        assertThat(api.put("/api/v1/admin/assessments/rules", badWeights, token).code()).isEqualTo(40001);

        // 阈值必须严格递增
        Map<String, Object> badLevels = new java.util.HashMap<>(ruleBody("10.00"));
        badLevels.put("levels", List.of(
                Map.of("level", 1, "min_score", "0.00", "recommend_priority", 3),
                Map.of("level", 2, "min_score", "90.00", "recommend_priority", 2),
                Map.of("level", 3, "min_score", "80.00", "recommend_priority", 1)));
        assertThat(api.put("/api/v1/admin/assessments/rules", badLevels, token).code()).isEqualTo(40001);

        // 基础档必须从 0.00 起
        Map<String, Object> badBase = new java.util.HashMap<>(ruleBody("10.00"));
        badBase.put("levels", List.of(
                Map.of("level", 1, "min_score", "10.00", "recommend_priority", 3),
                Map.of("level", 2, "min_score", "80.00", "recommend_priority", 2),
                Map.of("level", 3, "min_score", "90.00", "recommend_priority", 1)));
        assertThat(api.put("/api/v1/admin/assessments/rules", badBase, token).code()).isEqualTo(40001);

        // 合法的改动：拉新达标线 10 人、优选档降到 70
        Map<String, Object> good = new java.util.HashMap<>(ruleBody("10.00"));
        good.put("invite_target", 10);
        good.put("levels", List.of(
                Map.of("level", 1, "min_score", "0.00", "recommend_priority", 3),
                Map.of("level", 2, "min_score", "70.00", "recommend_priority", 2),
                Map.of("level", 3, "min_score", "90.00", "recommend_priority", 1)));
        ApiClient.ApiCall updated = api.put("/api/v1/admin/assessments/rules", good, token);
        assertCodeOk(updated, "改规则");
        assertThat(updated.data().path("invite_target").asInt()).isEqualTo(10);

        // 新规则对之后算出的账期生效：有效邀请 2 / 达标线 10 → 拉新 20 分；总分 70.00 → 优选
        ApprovedProvider provider = createApprovedProvider("LIC-SUPER-004");
        stub().setInvites(2);
        stub().setContributableTemplates(1);
        stub().setCoupon(0, "0.00");
        seedOrder(provider.providerId(), 3, null, 10, day(3));
        seedOrder(provider.providerId(), 3, null, 10, PERIOD.plusMonths(1).atDay(3));
        assessmentService.calculate(provider.providerId(), PERIOD.toString());

        JsonNode detail = api.get("/api/v1/provider/assessments/" + PERIOD, provider.token()).data();
        assertThat(item(detail, "INVITE").path("score").asText()).isEqualTo("20.00");
        assertThat(item(detail, "INVITE").path("target_value").asText()).contains("10");
        // 券 = 0 × 完成率 = 0（有券可出但一张没核销 → 该做而没做记 0）；过程 100
        assertThat(item(detail, "COUPON").path("score").asText()).isEqualTo("0.00");
        assertThat(item(detail, "PROCESS").path("score").asText()).isEqualTo("100.00");
        // (20×40 + 0×40 + 100×20) / 100 = 28.00
        assertThat(detail.path("total_score").asText()).isEqualTo("28.00");
        // 28 分不够优选，所以等级仍是基础；把优选阈值降到 20 再看一次分档（规则改了立刻生效）
        Map<String, Object> lower = new java.util.HashMap<>(good);
        lower.put("levels", List.of(
                Map.of("level", 1, "min_score", "0.00", "recommend_priority", 3),
                Map.of("level", 2, "min_score", "20.00", "recommend_priority", 2),
                Map.of("level", 3, "min_score", "90.00", "recommend_priority", 1)));
        assertCodeOk(api.put("/api/v1/admin/assessments/rules", lower, token), "再改阈值");
        // 阈值只影响之后算出的账期：已算出的那一期还是基础档（快照）
        assertThat(api.get("/api/v1/admin/assessments/" + scoreIdOf(provider.token()), token)
                .data().path("level").asInt()).isEqualTo(1);
        assessmentService.calculate(provider.providerId(), PERIOD.plusMonths(1).toString());
        assertThat(api.get("/api/v1/admin/assessments?period=" + PERIOD.plusMonths(1), token)
                .data().path("list").get(0).path("level").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT `level` FROM `provider` WHERE `id` = ?", Integer.class,
                provider.providerId())).isEqualTo(2);
    }

    @Test
    @DisplayName("超管名单之外：另一个运营账号仍然改不了（40300），读不受影响")
    void otherAdminAccountIsRejected() {
        ApprovedProvider provider = createApprovedProvider("LIC-SUPER-005");
        configureRule(0, "0.00", 30);
        seedOrder(provider.providerId(), 3, null, 10, day(3));
        assessmentService.calculate(provider.providerId(), PERIOD.toString());
        long scoreId = scoreIdOf(provider.token());

        String otherAdmin = adminToken();   // 自增 id 的另一个运营账号，不在名单里
        ApiClient.ApiCall call = api.post("/api/v1/admin/assessments/" + scoreId + "/overrides",
                Map.of("item_code", "PROCESS", "score", "100.00", "reason", "越权尝试"), otherAdmin);
        assertThat(call.code()).isEqualTo(40300);
        assertThat(call.message()).contains("不在超管名单");
        assertThat(api.put("/api/v1/admin/assessments/rules", ruleBody("10.00"), otherAdmin).code())
                .isEqualTo(40300);
        // 读得到（运营的日常动作）
        assertCodeOk(api.get("/api/v1/admin/assessments/" + scoreId, otherAdmin), "另一个运营读明细");
    }

}
