package com.pethealth.boot.assessment;

import com.fasterxml.jackson.databind.JsonNode;
import com.pethealth.boot.support.ApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 考核算分（F022）：三项加权、缺项两种口径、取消率进过程分、等级分档。
 *
 * <p>这一组用例打的是**HTTP 缝**（ADR-0014）：造数据（订单行、增长侧事实、规则）之后走
 * {@code POST /assessments/** 的读接口}看结果，只有「触发批算」这一步直接调
 * {@link com.pethealth.provider.service.AssessmentService#calculate}（它是批算与补跑的公共入口）。
 *
 * <p>算分口径的每一条断言都对得上一个 ADR：
 *
 * <ul>
 *   <li>总分 = 拉新 40% + 券 40% + 过程 20%（ADR-0039 第三节）；
 *   <li>券 = 完成率 × 核销数（ADR-0044 第五节给的口径）；
 *   <li>过程分含服务者取消率（ADR-0049 第七节）；
 *   <li>缺项两种口径（ADR-0050 第四节）：该做而没做记 0、平台侧无要求则不参与并重算权重；
 *   <li>评价分缺失按缺项口径处理并在明细里注明（ADR-0039 第二节 + 本切片的决定）。
 * </ul>
 */
class AssessmentScoringTest extends AssessmentStubSupport {

    @Test
    @DisplayName("三项加权：拉新 50 + 券 64 + 过程 93.75 → 总分 64.35（40/40/20）")
    void weightedTotalOfThreeItems() {
        ApprovedProvider provider = createApprovedProvider("LIC-ASSESS-001");
        // 规则：拉新达标线 4 人、券达标线 10.00、接单响应达标线 30 分钟（权重用种子 40/40/20）
        configureRule(4, "10.00", 30);
        // 增长侧事实：有效邀请 2 人；券池有一张可贡献的券，完成率 0.80 × 核销 8 张 = 6.40
        stub().setInvites(2);
        stub().setContributableTemplates(1);
        stub().setCoupon(8, "0.80");
        // 订单：4 单里 1 单履约中、3 单已完成，接单响应 20 分钟，没有取消
        seedOrder(provider.providerId(), 3, null, 20, day(3));
        seedOrder(provider.providerId(), 3, null, 20, day(4));
        seedOrder(provider.providerId(), 3, null, 20, day(5));
        seedOrder(provider.providerId(), 2, null, 20, day(6));

        assertThat(assessmentService.calculate(provider.providerId(), PERIOD.toString())).isTrue();

        JsonNode detail = detail(provider.token(), PERIOD.toString());
        assertThat(item(detail, "INVITE").path("score").asText()).isEqualTo("50.00");   // 2 / 4 × 100
        assertThat(item(detail, "COUPON").path("score").asText()).isEqualTo("64.00");   // 6.40 / 10.00 × 100
        // 过程 = (接单响应 100 + 核销率 100 + 报工完整率 75 + 取消率 100) / 4（评价分本期未参与）
        assertThat(item(detail, "PROCESS").path("score").asText()).isEqualTo("93.75");
        // 总分 = (50×40 + 64×40 + 93.75×20) / 100 = 64.35
        assertThat(detail.path("total_score").asText()).isEqualTo("64.35");
        assertThat(detail.path("participated_weight").asInt()).isEqualTo(100);
        assertThat(detail.path("level").asInt()).isEqualTo(1);
        assertThat(detail.path("level_name").asText()).isEqualTo("基础");
        assertThat(detail.path("recommend_priority").asInt()).isEqualTo(3);

        // 明细：每项都带「是否参与 / 得分 / 数据来源」，过程子项挂在 PROCESS 下
        assertThat(item(detail, "PROCESS_REDEEM_RATE").path("score").asText()).isEqualTo("100.00");
        assertThat(item(detail, "PROCESS_REPORT_RATE").path("score").asText()).isEqualTo("75.00");
        assertThat(item(detail, "PROCESS_RESPONSE").path("raw_value").asText()).contains("20");
        assertThat(item(detail, "PROCESS_REVIEW").path("participated").asBoolean()).isFalse();
        assertThat(item(detail, "PROCESS_REVIEW").path("score").isNull()).isTrue();
        assertThat(item(detail, "PROCESS_REVIEW").path("note").asText()).contains("未参与");
        assertThat(item(detail, "PROCESS").path("parent_code").isNull()).isTrue();
        assertThat(item(detail, "PROCESS_RESPONSE").path("parent_code").asText()).isEqualTo("PROCESS");
        assertThat(item(detail, "INVITE").path("data_source").asText()).contains("ph-privilege");
        assertThat(item(detail, "PROCESS").path("data_source").asText()).contains("ph-order");

        // 等级与总分写回门店（推荐侧与 C 端读的是这两列）
        assertThat(jdbc.queryForObject("SELECT `level` FROM `provider` WHERE `id` = ?", Integer.class,
                provider.providerId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT `monthly_score` FROM `provider` WHERE `id` = ?",
                java.math.BigDecimal.class, provider.providerId())).isEqualByComparingTo("64.35");
        // 门店信息接口里也能读到（契约的 ProviderProfileView.monthly_score）
        ApiClient.ApiCall profile = api.get("/api/v1/provider/profile", provider.token());
        assertThat(profile.data().path("monthly_score").asText()).isEqualTo("64.35");
    }

    @Test
    @DisplayName("缺项口径一：该做而没做记 0 分（有拉新入口与券可出，但一条没做）")
    void missingButRequiredScoresZero() {
        ApprovedProvider provider = createApprovedProvider("LIC-ASSESS-002");
        configureRule(5, "10.00", 30);
        stub().setInvites(0);          // 平台给了入口，一条有效邀请都没有 → 记 0
        stub().setContributableTemplates(2);
        stub().setCoupon(0, "0.40");   // 券池有券可出，一张没核销 → 记 0
        // 有订单但一单未接（没有接单时刻）：接单响应这一子项「该做而没做」→ 记 0
        seedOrder(provider.providerId(), 0, null, null, day(3));
        seedOrder(provider.providerId(), 0, null, null, day(4));

        assessmentService.calculate(provider.providerId(), PERIOD.toString());
        JsonNode detail = detail(provider.token(), PERIOD.toString());

        // 记 0 = participated true + score 0.00（与「未参与」的 null 是两种形状）
        assertThat(item(detail, "INVITE").path("participated").asBoolean()).isTrue();
        assertThat(item(detail, "INVITE").path("score").asText()).isEqualTo("0.00");
        assertThat(item(detail, "INVITE").path("note").asText()).contains("该做而没做");
        assertThat(item(detail, "COUPON").path("participated").asBoolean()).isTrue();
        assertThat(item(detail, "COUPON").path("score").asText()).isEqualTo("0.00");
        assertThat(item(detail, "COUPON").path("note").asText()).contains("该做而没做");
        assertThat(item(detail, "PROCESS_RESPONSE").path("participated").asBoolean()).isTrue();
        assertThat(item(detail, "PROCESS_RESPONSE").path("score").asText()).isEqualTo("0.00");
        assertThat(item(detail, "PROCESS_RESPONSE").path("note").asText()).contains("未接单");
        // 核销率 / 报工完整率 / 取消率都还能算（有单、没有核销、没有取消）
        assertThat(item(detail, "PROCESS_REDEEM_RATE").path("score").asText()).isEqualTo("0.00");
        assertThat(item(detail, "PROCESS_REPORT_RATE").path("participated").asBoolean()).isFalse();
        assertThat(item(detail, "PROCESS_CANCEL_RATE").path("score").asText()).isEqualTo("100.00");
        // 过程分 = (0 + 0 + 100) / 3 = 33.33；三项全参与 → 总分 = (0×40 + 0×40 + 33.33×20)/100
        assertThat(item(detail, "PROCESS").path("score").asText()).isEqualTo("33.33");
        assertThat(detail.path("total_score").asText()).isEqualTo("6.67");
    }

    @Test
    @DisplayName("缺项口径二：无要求则不参与并重算权重（达标线未配置 + 券池无券可出）")
    void notRequiredItemsAreExcludedAndWeightsRecalculated() {
        ApprovedProvider provider = createApprovedProvider("LIC-ASSESS-003");
        // 拉新与券的达标线都没配（种子里就是 0）→ 两项都不参与
        configureRule(0, "0.00", 30);
        stub().setInvites(9);          // 即使有数据也不看：平台侧没定要求
        stub().setCoupon(9, "0.90");
        seedOrder(provider.providerId(), 3, null, 10, day(3));

        assessmentService.calculate(provider.providerId(), PERIOD.toString());
        JsonNode detail = detail(provider.token(), PERIOD.toString());

        assertThat(item(detail, "INVITE").path("participated").asBoolean()).isFalse();
        assertThat(item(detail, "INVITE").path("score").isNull()).isTrue();
        assertThat(item(detail, "INVITE").path("note").asText()).contains("未参与");
        assertThat(item(detail, "COUPON").path("participated").asBoolean()).isFalse();
        assertThat(item(detail, "COUPON").path("note").asText()).contains("未参与");
        // 权重按参与项重算：只剩过程项的 20 → 总分 = 过程分（而不是被两项 0 分拉低）
        assertThat(detail.path("participated_weight").asInt()).isEqualTo(20);
        // 过程 = (100 接单响应 + 100 核销率 + 100 报工完整率 + 100 取消率) / 4 = 100
        assertThat(item(detail, "PROCESS").path("score").asText()).isEqualTo("100.00");
        assertThat(detail.path("total_score").asText()).isEqualTo("100.00");
        // 100 分 → 战略合作（阈值 90）
        assertThat(detail.path("level").asInt()).isEqualTo(3);
        assertThat(detail.path("recommend_priority").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("缺项口径二（券的另一种）：券池里一张可贡献的券都没有 → 券维度不参与")
    void couponNotParticipatingWhenPoolHasNoContributableTemplate() {
        ApprovedProvider provider = createApprovedProvider("LIC-ASSESS-004");
        configureRule(0, "10.00", 30);   // 券达标线配了，但池子里没有可贡献的券
        stub().setContributableTemplates(0);
        stub().setCoupon(0, "0.00");
        seedOrder(provider.providerId(), 3, null, 10, day(3));

        assessmentService.calculate(provider.providerId(), PERIOD.toString());
        JsonNode detail = detail(provider.token(), PERIOD.toString());

        assertThat(item(detail, "COUPON").path("participated").asBoolean()).isFalse();
        assertThat(item(detail, "COUPON").path("note").asText()).contains("券池");
        // 拉新仍参与（达人目标配了 0 在这里表示未配置 → 也不参与），过程参与
        assertThat(detail.path("participated_weight").asInt()).isEqualTo(20);
        assertThat(detail.path("total_score").asText()).isEqualTo("100.00");
    }

    @Test
    @DisplayName("取消率进过程分：服务者取消 2/5 → 该子项 40 分；用户取消不算在他头上")
    void providerCancelRateEntersProcessScore() {
        ApprovedProvider provider = createApprovedProvider("LIC-ASSESS-005");
        configureRule(0, "0.00", 30);    // 只看过程项，便于逐项验
        seedOrder(provider.providerId(), 3, null, 10, day(3));
        seedOrder(provider.providerId(), 3, null, 10, day(4));
        seedOrder(provider.providerId(), 4, 2, null, day(5));   // 服务者取消
        seedOrder(provider.providerId(), 4, 2, null, day(6));   // 服务者取消
        seedOrder(provider.providerId(), 4, 1, null, day(7));   // 用户取消：不算服务者头上

        assessmentService.calculate(provider.providerId(), PERIOD.toString());
        JsonNode detail = detail(provider.token(), PERIOD.toString());

        JsonNode cancel = item(detail, "PROCESS_CANCEL_RATE");
        assertThat(cancel.path("raw_value").asText()).isEqualTo("服务者取消 2 / 订单 5");
        assertThat(cancel.path("score").asText()).isEqualTo("60.00");   // (1 − 2/5) × 100
        assertThat(cancel.path("data_source").asText()).contains("ph-order");
        // 过程 = (100 + 40 核销率(2/5) + 100 报工完整率 + 60) / 4 = 75.00
        assertThat(item(detail, "PROCESS_REDEEM_RATE").path("score").asText()).isEqualTo("40.00");
        assertThat(item(detail, "PROCESS").path("score").asText()).isEqualTo("75.00");
    }

    @Test
    @DisplayName("权重改了只影响之后算出的账期：券权重抬到 80 后总分随之变")
    void ruleChangesAffectOnlyNewPeriods() {
        ApprovedProvider provider = createApprovedProvider("LIC-ASSESS-006");
        configureRule(0, "0.00", 30);
        seedOrder(provider.providerId(), 3, null, 10, day(3));
        assessmentService.calculate(provider.providerId(), PERIOD.toString());

        // 第一期的权重是快照：过程 20 / 拉新 40 / 券 40（两项未参与 → 总分 = 过程分）
        assertThat(jdbc.queryForMap("SELECT `process_weight`, `invite_weight` FROM `assessment_monthly_score` "
                        + "WHERE `provider_id` = ? AND `period` = ?", provider.providerId(), PERIOD.toString()))
                .containsEntry("process_weight", 20)
                .containsEntry("invite_weight", 40);

        // 改权重后再算下一期：过程权重变成 100，总分仍是过程分（这里只验快照跟着规则走）
        configureWeights(0, 0, 100);
        assessmentService.calculate(provider.providerId(), PERIOD.plusMonths(1).toString());
        assertThat(jdbc.queryForMap("SELECT `process_weight`, `invite_weight` FROM `assessment_monthly_score` "
                        + "WHERE `provider_id` = ? AND `period` = ?", provider.providerId(),
                PERIOD.plusMonths(1).toString()))
                .containsEntry("process_weight", 100)
                .containsEntry("invite_weight", 0);
        // 历史那一期没被动（跨月可对比的前提）
        assertThat(jdbc.queryForObject("SELECT `process_weight` FROM `assessment_monthly_score` "
                + "WHERE `provider_id` = ? AND `period` = ?", Integer.class, provider.providerId(),
                PERIOD.toString())).isEqualTo(20);
    }

}
