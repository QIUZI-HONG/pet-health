package com.pethealth.boot.provider;

import com.pethealth.api.privilege.CouponDtos;
import com.pethealth.boot.support.ApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 服务者身份解析的接线验证（ADR-0044「需要协调」那一处）。
 *
 * <p><b>这个类刻意不引入任何桩</b>：{@code ProviderAccessApi} 的实现由 ph-provider 的
 * {@code ProviderAccessAdapter} 提供，本类跑的上下文里只有它——没有它，下面的用例会拿到
 * {@code 50300}「服务者身份解析尚未接线」。这就是「补一条集成测试证明它接上了」的落点：
 * 桩能过的用例（{@code PrivilegeTestSupport} 那五个类）证明不了这件事。
 *
 * <p>三条链路一次测全（正是接口的三个方法）：
 *
 * <ul>
 *   <li>{@code findProviderId} → 券池模板列表与「我的贡献」按绑定门店返回；
 *   <li>{@code isAdmin} → 兑现「技师的绑定不算管理员」由 {@code ProviderConsole} 判，
 *       这里验证管理员的绑定能通过（技师的越权用例在 ph-privilege 那一侧）；
 *   <li>{@code providerNames} → 贡献视图里的门店名（运营/服务者看到的「谁承诺的」）。
 * </ul>
 */
class ProviderCouponWiringTest extends ProviderApiTestSupport {

    @BeforeEach
    void cleanCouponPool() {
        // 券池那几张表不在 IntegrationTestBase 的清理名单里（那是跨切片的共享基类），本切片自己清
        jdbc.execute("DELETE FROM `coupon_contribution_log`");
        jdbc.execute("DELETE FROM `coupon_contribution`");
        jdbc.execute("DELETE FROM `coupon_template`");
    }

    @Test
    @DisplayName("接上之后：券池模板列表按「绑定 + 管理员」放行，贡献里带上门店 id 与门店名")
    void providerIdentityIsWiredByAdapter() {
        ApprovedProvider provider = createApprovedProvider("91310000WIRING001X");
        long templateId = insertProviderCostTemplate("CP-901", "接线验证券");

        // ① findProviderId：未接线时这里是 50300（服务者身份解析尚未接线）
        ApiClient.ApiCall templates = api.get("/api/v1/provider/coupon-pool/templates", provider.token());
        assertCodeOk(templates, "券池模板列表");
        assertThat(templates.data().path("total").asLong()).isEqualTo(1);
        assertThat(templates.data().path("list").get(0).path("code").asText()).isEqualTo("CP-901");

        // ② isAdmin + ③ providerNames：承诺额度后，视图里的门店是刚入驻的那家
        ApiClient.ApiCall committed = api.post("/api/v1/provider/coupon-contributions",
                new CouponDtos.CouponContributionRequest(templateId, 5, null, "接线用例"), provider.token());
        assertCodeOk(committed, "承诺额度");
        assertThat(committed.data().path("provider_id").asLong()).isEqualTo(provider.providerId());
        assertThat(committed.data().path("provider_name").asText()).isEqualTo("测试动物医院");
        assertThat(committed.data().path("available_count").asInt()).isEqualTo(5);
        assertThat(committed.data().path("completion_rate").asText()).isEqualTo("0.00");
    }

    @Test
    @DisplayName("没有绑定门店的账号回 40400（不再是 50300：接线之后按「没有门店」处理）")
    void unboundAccountIsNotFound() {
        // 只有登录账号、没有入驻申请：findProviderId 返回空 → ProviderConsole 按「还没有门店」处理
        String token = providerToken();

        ApiClient.ApiCall call = api.get("/api/v1/provider/coupon-pool/templates", token);
        assertThat(call.code()).isEqualTo(40400);
        assertThat(call.message()).contains("还没有关联的服务者");
    }

    /**
     * 插一条服务者成本的券模板（券模板由平台创建，没有种子数据；`provider.yaml` 的
     * {@code cost_bearer=1} 才可贡献）。{@code scope_type=0} 表示不限适用范围，
     * 这样断言不会被目录数据影响。
     */
    private long insertProviderCostTemplate(String code, String name) {
        jdbc.update("INSERT INTO `coupon_template` "
                        + "(`code`, `name`, `face_value`, `min_amount`, `valid_days`, `cost_bearer`, "
                        + "`scope_type`, `scope_codes`, `status`, `created_by`) "
                        + "VALUES (?, ?, 30.00, 50.00, 30, 1, 0, '', 1, 0)",
                code, name);
        return jdbc.queryForObject("SELECT `id` FROM `coupon_template` WHERE `code` = ?", Long.class, code);
    }
}
