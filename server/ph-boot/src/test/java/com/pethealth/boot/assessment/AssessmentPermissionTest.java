package com.pethealth.boot.assessment;

import com.pethealth.boot.support.ApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 考核的权限边界（ADR-0037 第一节的矩阵、ADR-0012 的登录域）：
 *
 * <ul>
 *   <li><b>服务者只能看自己的</b>：别人的账期与不存在的账期同码（40400，docs/conventions.md）；
 *   <li><b>覆盖单项分与规则配置只归超级管理员</b>：不是一个「越权」用例就能覆盖的判定，
 *       所以这里分三层验——登录域（C 端 / 服务者端调不了 admin 接口）、
 *       超管名单（本类跑在**没配名单**的上下文里：谁都不能改，fail-closed）、
 *       以及名单里有一个账号时的两种结果（见 {@code AssessmentSuperAdminTest}）。
 * </ul>
 */
class AssessmentPermissionTest extends AssessmentStubSupport {

    @Test
    @DisplayName("服务者只能看自己的：B 店看不到 A 店的账期，自己的账期 B 店读是 40400")
    void providerSeesOnlyOwnAssessment() {
        ApprovedProvider alpha = createApprovedProvider("LIC-PERM-001");
        ApprovedProvider beta = createApprovedProvider("LIC-PERM-002");
        configureRule(0, "0.00", 30);
        seedOrder(alpha.providerId(), 3, null, 10, day(3));
        assessmentService.calculate(alpha.providerId(), PERIOD.toString());

        // A 店看得到自己的
        assertCodeOk(api.get("/api/v1/provider/assessments/" + PERIOD, alpha.token()), "A 店读自己的考核");
        // B 店读同一个账期：40400（与「不存在」同码，不泄露 A 店有没有考核）
        ApiClient.ApiCall betaDetail = api.get("/api/v1/provider/assessments/" + PERIOD, beta.token());
        assertThat(betaDetail.code()).isEqualTo(40400);
        // B 店的列表是空的（不是「给 A 店的分」）
        ApiClient.ApiCall betaList = api.get("/api/v1/provider/assessments", beta.token());
        assertThat(betaList.data().path("total").asLong()).isZero();
    }

    @Test
    @DisplayName("登录域：C 端令牌与服务者令牌都调不了 admin 域的考核接口")
    void loginDomainIsEnforced() {
        String cEnd = api.registerAndGetAccessToken(nextPhone());
        ApiClient.ApiCall cEndCall = api.get("/api/v1/admin/assessments?period=" + PERIOD, cEnd);
        assertThat(cEndCall.status()).isEqualTo(401);
        assertThat(cEndCall.code()).isEqualTo(40100);

        ApprovedProvider provider = createApprovedProvider("LIC-PERM-003");
        ApiClient.ApiCall providerCall = api.get("/api/v1/admin/assessments/rules", provider.token());
        assertThat(providerCall.status()).isEqualTo(401);
        assertThat(providerCall.code()).isEqualTo(40100);
    }

    @Test
    @DisplayName("超管名单没配时 fail-closed：任何 admin 域身份都不能改规则或覆盖单项分")
    void overrideIsClosedUntilSuperAdminListIsConfigured() {
        ApprovedProvider provider = createApprovedProvider("LIC-PERM-004");
        configureRule(0, "0.00", 30);
        seedOrder(provider.providerId(), 3, null, 10, day(3));
        assessmentService.calculate(provider.providerId(), PERIOD.toString());
        long scoreId = scoreIdOf(provider.token());

        String admin = adminToken();
        ApiClient.ApiCall overridden = api.post("/api/v1/admin/assessments/" + scoreId + "/overrides",
                Map.of("item_code", "PROCESS", "score", "100.00", "reason", "测试"), admin);
        assertThat(overridden.code()).isEqualTo(40300);
        assertThat(overridden.message()).contains("超级管理员");

        ApiClient.ApiCall rules = api.put("/api/v1/admin/assessments/rules",
                ruleBody("10.00"), admin);
        assertThat(rules.code()).isEqualTo(40300);

        // 读接口不受影响：运营的日常动作是「看得到、改不了」
        assertCodeOk(api.get("/api/v1/admin/assessments/rules", admin), "运营读规则");
        assertCodeOk(api.get("/api/v1/admin/assessments?period=" + PERIOD, admin), "运营读列表");
        assertCodeOk(api.get("/api/v1/admin/assessments/" + scoreId, admin), "运营读明细");
        // 也没留下任何覆盖痕迹
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `assessment_override_log`", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("没有绑定的服务者域账号：读考核是 40400（门店这个资源对他还不存在）")
    void unboundProviderGetsNotFound() {
        ApiClient.ApiCall call = api.get("/api/v1/provider/assessments", providerToken());
        assertThat(call.code()).isEqualTo(40400);
    }

}
