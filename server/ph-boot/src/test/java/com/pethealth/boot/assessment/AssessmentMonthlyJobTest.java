package com.pethealth.boot.assessment;

import com.fasterxml.jackson.databind.JsonNode;
import com.pethealth.boot.support.ApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 月度批算（F022）：**每月 1 日算上月**（ADR-0039 第三节）与**幂等**。
 *
 * <p>批算没有 HTTP 入口（它是 {@code @Scheduled}），所以这里直接调
 * {@link com.pethealth.provider.service.AssessmentMonthlyJob#run()}——与
 * {@code QualificationExpiryTest} 同一取舍：与其让这条规则完全没有测试，
 * 不如直接调它的入口，而断言仍然只看外部可观察状态（库里的分表、接口的返回）。
 */
class AssessmentMonthlyJobTest extends AssessmentStubSupport {

    @Test
    @DisplayName("批算算的是**上月**：跑一次落一条，再跑一次不再落（幂等）")
    void calculatesLastMonthOnce() {
        ApprovedProvider provider = createApprovedProvider("LIC-JOB-001");
        // 待审核的服务者（有申请单、还没通过）：批算不该给他落分
        long pendingProviderId = submitApplication(providerToken(), "待审核门店", "13700001111",
                "LIC-JOB-PENDING", null).data().path("provider").path("id").asLong();

        YearMonth month = lastMonth();
        configureRule(0, "0.00", 30);
        stub().setInvites(3);
        stub().setContributableTemplates(1);
        stub().setCoupon(4, "1.00");
        seedOrder(provider.providerId(), 3, null, 10, month.atDay(5));
        seedOrder(provider.providerId(), 3, null, 10, month.atDay(6));

        var first = monthlyJob.run();
        assertThat(first.period()).isEqualTo(month.toString());
        assertThat(first.calculated()).isEqualTo(1);
        assertThat(scoreRowCount(provider.providerId(), month)).isEqualTo(1);
        // 待审核的门店没有分（批算只遍历「正常」状态的服务者）
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `assessment_monthly_score` WHERE `provider_id` = ?",
                Integer.class, pendingProviderId)).isZero();

        // 再跑一次（重跑 / 多实例 / 运营补跑）：唯一键兜底，不产生第二条
        var second = monthlyJob.run();
        assertThat(second.calculated()).isZero();
        assertThat(scoreRowCount(provider.providerId(), month)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `assessment_item_score` WHERE `provider_id` = ?",
                Integer.class, provider.providerId())).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `assessment_override_log`", Integer.class)).isZero();
    }

    @Test
    @DisplayName("列表接口：账期倒序、按账期过滤；明细里过程子项齐全")
    void listShowsPeriodsInReverseOrder() {
        ApprovedProvider provider = createApprovedProvider("LIC-JOB-002");
        configureRule(0, "0.00", 30);
        seedOrder(provider.providerId(), 3, null, 10, day(3));
        assessmentService.calculate(provider.providerId(), PERIOD.toString());
        assessmentService.calculate(provider.providerId(), PERIOD.plusMonths(1).toString());

        ApiClient.ApiCall list = api.get("/api/v1/provider/assessments", provider.token());
        assertCodeOk(list, "我的考核列表");
        assertThat(list.data().path("total").asLong()).isEqualTo(2);
        JsonNode firstRow = list.data().path("list").get(0);
        assertThat(firstRow.path("period").asText()).isEqualTo(PERIOD.plusMonths(1).toString());
        // 服务者看自己的列表：契约里 provider_id / provider_name 只在运营侧有值
        assertThat(firstRow.path("provider_name").isNull()).isTrue();

        ApiClient.ApiCall filtered = api.get("/api/v1/provider/assessments?period=" + PERIOD, provider.token());
        assertThat(filtered.data().path("total").asLong()).isEqualTo(1);
        assertThat(filtered.data().path("list").get(0).path("period").asText()).isEqualTo(PERIOD.toString());

        JsonNode detail = api.get("/api/v1/provider/assessments/" + PERIOD, provider.token()).data();
        assertThat(detail.path("items")).hasSize(8);
        assertThat(detail.path("overrides")).isEmpty();
    }

    @Test
    @DisplayName("账期格式不对 → 40001（不是 50000），没有考核记录的账期 → 40400")
    void periodFormatIsValidated() {
        ApprovedProvider provider = createApprovedProvider("LIC-JOB-003");
        ApiClient.ApiCall bad = api.get("/api/v1/provider/assessments/2026-13", provider.token());
        assertThat(bad.code()).isEqualTo(40001);
        ApiClient.ApiCall missing = api.get("/api/v1/provider/assessments/2020-01", provider.token());
        assertThat(missing.code()).isEqualTo(40400);
    }
}
