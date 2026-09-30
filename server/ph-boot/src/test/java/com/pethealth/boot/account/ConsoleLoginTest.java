package com.pethealth.boot.account;

import com.pethealth.api.app.LoginRequest;
import com.pethealth.api.app.RefreshRequest;
import com.pethealth.boot.support.ApiClient;
import com.pethealth.boot.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 两个后台的登录入口（F021）——2026-09-30 验收里两个后台的头号缺口。
 *
 * <p>在此之前 20 个后台页面都实现了、每页都真的调接口，但没有登录入口：契约里没有 auth 路径，
 * 生产代码只签发 APP 域令牌，所以真实使用中一个页面都到不了。这一层盯四件事：
 *
 * <ol>
 *   <li><b>服务者后台登得进去，而且第一份入驻申请走的就是这条路</b>：注册（C 端）→
 *       provider 域登录 → 提交入驻。这是 BPM-4 的真实链路，也说明「要求先有服务者绑定才能
 *       登录」那种做法会把第一份申请锁在门外（鸡生蛋）；
 *   <li><b>登进来不等于看得到</b>：服务者后台**不设登录准入**，但未绑定账号读门店接口仍是
 *       40400——授权在接口层，不在登录层（ADR-0035 的 Consequences 写明过）；
 *   <li><b>运营后台是名单制</b>：不在名单里 40300（名单为空时谁都进不去，fail-closed）；
 *   <li><b>域不通用</b>：C 端的 Refresh 打不动后台的换发接口，而 provider 的换出来还是 provider。
 * </ol>
 *
 * <p>运营名单的**正向**用例在 {@link ConsoleAdminAdmissionTest}——那里能在跑起来之后再把名单
 * 配成刚拿到的账号 id（本类用的是 {@code @TestPropertySource}，值必须在上下文启动前就定下来，
 * 而账号 id 是运行时才知道的）。
 */
class ConsoleLoginTest extends IntegrationTestBase {

    private ApiClient api;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUpClient() {
        api = new ApiClient(rest, objectMapper);
    }

    @Test
    @DisplayName("服务者后台登得进去，且第一份入驻申请就是这条路：注册 → provider 登录 → 提交入驻")
    void providerLoginUnlocksTheFirstOnboardingApplication() {
        ApiClient.ApiCall user = api.register("13800139200");
        String appToken = user.data().path("access_token").asText();
        long userId = user.data().path("user").path("id").asLong();

        // 字段照 OnboardingApplicationRequest 的必填项给（漏了必填会被 40001 拦下——
        // 第一版就漏了 applicant_name / contact_phone，这条用例当场变红）
        Map<String, Object> application = Map.of(
                "name", "登录测试门店", "type", 1, "category", 1,
                "address", "上海市测试路 1 号", "contact_phone", "13800139200",
                "applicant_name", "张三",
                // 类型 1（医院）按 ADR-0035 必须提交执业许可——缺材料也是 40001
                "qualifications", List.of(Map.of(
                        "type", 1, "name", "营业执照", "cert_no", "LIC-CONSOLE-001",
                        "valid_until", "2030-12-31")));

        // C 端令牌提交入驻拿不到——那条路径属于 provider 域。这正是原先的死结：
        // 「要求先有绑定才能登录」的话，第一份申请永远提交不了
        assertThat(api.post("/api/v1/provider/onboarding/applications", application, appToken).code())
                .as("C 端令牌不能调服务者后台的接口（独立登录域）").isEqualTo(40100);

        ApiClient.ApiCall login = api.post("/api/v1/provider/auth/login",
                new LoginRequest("13800139200", ApiClient.DEFAULT_PASSWORD));
        assertThat(login.code()).as("服务者后台登录：%s", login.body()).isZero();
        String providerToken = login.data().path("access_token").asText();
        assertThat(login.data().path("user").path("id").asLong())
                .as("后台账号就是 C 端账号（ADR-0035 决定 4）").isEqualTo(userId);
        // 域写在令牌里：C 端接口不认它
        assertThat(api.get("/api/v1/app/users/me", providerToken).code()).isEqualTo(40100);

        assertThat(api.post("/api/v1/provider/onboarding/applications", application, providerToken).code())
                .as("拿到 provider 令牌后就能提交入驻了（BPM-4 的第一步）").isZero();
    }

    @Test
    @DisplayName("登进来不等于看得到：未绑定的账号读门店订单仍是 40400")
    void loggedInButUnboundStillSeesNothing() {
        api.register("13800139201");
        String providerToken = api.post("/api/v1/provider/auth/login",
                        new LoginRequest("13800139201", ApiClient.DEFAULT_PASSWORD))
                .data().path("access_token").asText();

        assertThat(api.get("/api/v1/provider/orders?page=1&page_size=10", providerToken).code())
                .as("未绑定账号的门店订单列表按「不存在」处理").isEqualTo(40400);
    }

    @Test
    @DisplayName("运营后台是名单制：不在名单里 40300；同一个账号在服务者后台能进（那一端不设准入）")
    void adminLoginRejectsAccountOutsideAllowlist() {
        api.register("13800139202");

        ApiClient.ApiCall admin = api.post("/api/v1/admin/auth/login",
                new LoginRequest("13800139202", ApiClient.DEFAULT_PASSWORD));
        assertThat(admin.code()).as("口令是对的，只是这个账号不在运营后台名单里").isEqualTo(40300);
        assertThat(admin.message()).contains("不在运营后台名单里");

        assertThat(api.post("/api/v1/provider/auth/login",
                new LoginRequest("13800139202", ApiClient.DEFAULT_PASSWORD)).code())
                .as("服务者后台不设准入").isZero();
    }

    @Test
    @DisplayName("错误口令仍是 40100，且不泄露手机号是否注册过")
    void wrongPasswordStaysUnauthorized() {
        api.register("13800139203");

        ApiClient.ApiCall login = api.post("/api/v1/admin/auth/login",
                new LoginRequest("13800139203", "WrongPassw0rd"));
        assertThat(login.code()).isEqualTo(40100);
        assertThat(login.message()).doesNotContain("不存在");
    }

    @Test
    @DisplayName("域不通用：C 端的 Refresh 换不出后台令牌，而且这次尝试会消费掉它")
    void refreshIsDomainBound() {
        ApiClient.ApiCall user = api.register("13800139204");
        String appRefresh = user.data().path("refresh_token").asText();

        assertThat(api.post("/api/v1/provider/auth/refresh", new RefreshRequest(appRefresh)).code())
                .as("C 端的 Refresh 打服务者后台的换发接口").isEqualTo(40100);
        // 一次性使用：无论打哪个端，用掉就用掉了（ADR-0012）
        assertThat(api.post("/api/v1/app/auth/refresh", new RefreshRequest(appRefresh)).code())
                .isEqualTo(40101);
    }

    @Test
    @DisplayName("provider 令牌可以用来换发，换出来的还是 provider 域")
    void providerRefreshKeepsDomain() {
        api.register("13800139205");
        ApiClient.ApiCall login = api.post("/api/v1/provider/auth/login",
                new LoginRequest("13800139205", ApiClient.DEFAULT_PASSWORD));

        ApiClient.ApiCall refreshed = api.post("/api/v1/provider/auth/refresh",
                new RefreshRequest(login.data().path("refresh_token").asText()));
        assertThat(refreshed.code()).as("provider 的 Refresh 换发：%s", refreshed.body()).isZero();

        String access = refreshed.data().path("access_token").asText();
        assertThat(api.get("/api/v1/app/users/me", access).code())
                .as("换出来的是 provider 域（C 端接口不认它）").isEqualTo(40100);
        assertThat(api.get("/api/v1/provider/onboarding/applications", access).code())
                .as("provider 域接口认它").isZero();
    }
}
