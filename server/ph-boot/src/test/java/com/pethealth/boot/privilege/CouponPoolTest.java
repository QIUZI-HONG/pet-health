package com.pethealth.boot.privilege;

import com.pethealth.boot.support.ApiClient;
import com.pethealth.catalog.api.Price;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 券池的服务者侧与运营侧（切片 #110，决策见 ADR-0037 第三节 / ADR-0044）。
 *
 * <p>这一层盯四件事：**券模板只能由平台建**、**服务者承诺的是额度不是发放**、
 * **越权一律 40400**、**券池总览的对账口径**（实例数 = 已发放 = 已核销 + 未过期未核销 +
 * 已过期未核销）。
 */
class CouponPoolTest extends PrivilegeTestSupport {

    @Test
    @DisplayName("运营建券模板：编码不可复用、适用范围必须在标准目录里存在、成本归属创建后不可改")
    void adminCreatesTemplate() {
        String admin = adminToken();

        ApiClient.ApiCall created = createProviderCostTemplate(admin, "CP-001", "30.00", "100.00");
        assertCodeOk(created, "建服务者成本券模板");
        assertThat(created.data().path("code").asText()).isEqualTo("CP-001");
        assertThat(created.data().path("face_value").asText()).isEqualTo("30.00");
        assertThat(created.data().path("min_amount").asText()).isEqualTo("100.00");
        assertThat(created.data().path("cost_bearer").asInt()).isEqualTo(1);
        assertThat(created.data().path("scope_desc").asText()).isEqualTo("全部服务");
        long templateId = created.data().path("id").asLong();

        // 编码不可复用
        assertThat(createProviderCostTemplate(admin, "CP-001", "10.00", "0.00").code()).isEqualTo(40900);

        // 适用范围：限分类时编码必须能在标准目录里找到（V21 的种子分类 HOSPITAL）
        ApiClient.ApiCall scoped = api.post("/api/v1/admin/coupon-templates", Map.of(
                "code", "CP-002", "name", "洗护立减", "face_value", "20.00", "min_amount", "0.00",
                "valid_days", 60, "cost_bearer", 1, "scope_type", 1,
                "scope_codes", List.of("GROOMING")), admin);
        assertCodeOk(scoped, "限分类的券模板");
        assertThat(scoped.data().path("scope_desc").asText()).contains("洗护");
        long scopedId = scoped.data().path("id").asLong();

        ApiClient.ApiCall badScope = api.post("/api/v1/admin/coupon-templates", Map.of(
                "code", "CP-003", "name", "不存在的分类", "face_value", "20.00", "valid_days", 30,
                "cost_bearer", 1, "scope_type", 1, "scope_codes", List.of("NOPE")), admin);
        assertThat(badScope.code()).isEqualTo(40001);
        assertThat(badScope.message()).contains("NOPE");

        // 编码与成本归属不可改
        ApiClient.ApiCall renamed = api.put("/api/v1/admin/coupon-templates/" + templateId, Map.of(
                "code", "CP-009", "name", "改名", "face_value", "30.00", "valid_days", 30,
                "cost_bearer", 1), admin);
        assertThat(renamed.code()).isEqualTo(40001);
        ApiClient.ApiCall switchCost = api.put("/api/v1/admin/coupon-templates/" + templateId, Map.of(
                "code", "CP-001", "name", "改成平台补贴", "face_value", "30.00", "valid_days", 30,
                "cost_bearer", 2), admin);
        assertThat(switchCost.code()).isEqualTo(40001);

        // 面额必须大于 0（免费体验用券表达，不是把面额写成 0）
        assertThat(api.post("/api/v1/admin/coupon-templates", Map.of(
                "code", "CP-004", "name", "零面额", "face_value", "0.00", "valid_days", 30,
                "cost_bearer", 1), admin).code()).isEqualTo(40001);

        // 上下架：停用后服务者侧看不到它
        assertCodeOk(api.put("/api/v1/admin/coupon-templates/" + scopedId + "/status",
                Map.of("status", 0), admin), "停用");
        ApprovedProvider provider = createApprovedProvider("LIC-COUPON-001");
        ApiClient.ApiCall visible = api.get("/api/v1/provider/coupon-pool/templates", provider.token());
        assertThat(visible.data().path("list")).hasSize(1);
        assertThat(visible.data().path("list").get(0).path("code").asText()).isEqualTo("CP-001");
    }

    @Test
    @DisplayName("服务者选券承诺额度：可发放 = 承诺额度；调整不得低于已发出；撤回把余量收回")
    void providerCommitsAndAdjusts() {
        String admin = adminToken();
        long templateId = createProviderCostTemplate(admin, "CP-010", "30.00", "100.00")
                .data().path("id").asLong();
        ApprovedProvider provider = createApprovedProvider("LIC-COUPON-002");
        String token = provider.token();

        ApiClient.ApiCall committed = api.post("/api/v1/provider/coupon-contributions",
                Map.of("template_id", templateId, "total_count", 200, "remark", "开业活动"), token);
        assertCodeOk(committed, "承诺额度");
        assertThat(committed.data().path("total_count").asInt()).isEqualTo(200);
        assertThat(committed.data().path("available_count").asInt()).isEqualTo(200);
        assertThat(committed.data().path("issued_count").asInt()).isZero();
        assertThat(committed.data().path("status").asInt()).isEqualTo(1);
        long contributionId = committed.data().path("id").asLong();

        // 同一模板第二次承诺 → 409（一个服务者对一个模板只有一条额度账）
        ApiClient.ApiCall again = api.post("/api/v1/provider/coupon-contributions",
                Map.of("template_id", templateId, "total_count", 50), token);
        assertThat(again.code()).isEqualTo(40900);

        // 调额度：200 → 100
        ApiClient.ApiCall adjusted = api.put("/api/v1/provider/coupon-contributions/" + contributionId,
                Map.of("template_id", templateId, "total_count", 100, "remark", "缩到 100"), token);
        assertCodeOk(adjusted, "调额度");
        assertThat(adjusted.data().path("total_count").asInt()).isEqualTo(100);
        assertThat(adjusted.data().path("available_count").asInt()).isEqualTo(100);

        // 撤回：余量归零、状态转「已停止发放」，且**幂等**（重复撤回不报 404）
        ApiClient.ApiCall withdrawn = api.delete("/api/v1/provider/coupon-contributions/" + contributionId,
                token);
        assertCodeOk(withdrawn, "撤回");
        assertThat(withdrawn.data().path("status").asInt()).isEqualTo(2);
        assertThat(withdrawn.data().path("total_count").asInt()).isZero();
        assertThat(withdrawn.data().path("available_count").asInt()).isZero();
        assertCodeOk(api.delete("/api/v1/provider/coupon-contributions/" + contributionId, token),
                "重复撤回");

        // 重新启用并给新额度（还是同一条记录，额度账的历史是连续的一段）
        ApiClient.ApiCall reactivated = api.put("/api/v1/provider/coupon-contributions/" + contributionId,
                Map.of("template_id", templateId, "total_count", 60, "status", 1), token);
        assertThat(reactivated.data().path("status").asInt()).isEqualTo(1);
        assertThat(reactivated.data().path("total_count").asInt()).isEqualTo(60);

        // 额度流水（append-only）：承诺 → 调额 → 撤回 → 调额，四条都在
        ApiClient.ApiCall detail = api.get("/api/v1/provider/coupon-contributions/" + contributionId,
                token);
        assertThat(detail.data().path("logs")).hasSize(4);
        assertThat(detail.data().path("logs").get(0).path("action").asInt()).isEqualTo(1);
        assertThat(detail.data().path("logs").get(2).path("action").asInt()).isEqualTo(3);

        // 运营侧的总览看得到这条贡献（承诺 60、可发放 60）
        ApiClient.ApiCall overview = api.get("/api/v1/admin/coupon-pool/overview", admin);
        assertThat(overview.data().path("contribution_count").asInt()).isEqualTo(1);
        assertThat(overview.data().path("committed_total").asInt()).isEqualTo(60);
        assertThat(overview.data().path("available_total").asInt()).isEqualTo(60);
        assertThat(overview.data().path("reconciliation").path("balanced").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("越权：服务者只能动自己的贡献（别人的一律 40400），且平台补贴券不能贡献")
    void otherProviderCannotTouchMyContribution() {
        String admin = adminToken();
        long providerCostId = createProviderCostTemplate(admin, "CP-020", "30.00", "0.00")
                .data().path("id").asLong();
        long subsidyId = createPlatformSubsidyTemplate(admin, "CP-021", "50.00", null);

        ApprovedProvider mine = createApprovedProvider("LIC-COUPON-003");
        ApprovedProvider other = createApprovedProvider("LIC-COUPON-004");
        long contributionId = api.post("/api/v1/provider/coupon-contributions",
                        Map.of("template_id", providerCostId, "total_count", 10), mine.token())
                .data().path("id").asLong();

        // 别人的贡献：读 / 改 / 撤回都是 40400
        assertThat(api.get("/api/v1/provider/coupon-contributions/" + contributionId, other.token())
                .code()).isEqualTo(40400);
        assertThat(api.put("/api/v1/provider/coupon-contributions/" + contributionId,
                Map.of("template_id", providerCostId, "total_count", 999), other.token()).code())
                .isEqualTo(40400);
        assertThat(api.delete("/api/v1/provider/coupon-contributions/" + contributionId,
                other.token()).code()).isEqualTo(40400);
        assertThat(api.get("/api/v1/provider/coupon-contributions/" + contributionId + "/coupons",
                other.token()).code()).isEqualTo(40400);
        // 自己的额度没被动过
        assertThat(jdbc.queryForObject("SELECT total_count FROM coupon_contribution WHERE id = ?",
                Integer.class, contributionId)).isEqualTo(10);

        // 平台补贴券对服务者等于不存在（既看不到、也不能承诺额度）
        ApiClient.ApiCall templates = api.get("/api/v1/provider/coupon-pool/templates", mine.token());
        assertThat(templates.data().path("list")).hasSize(1);
        assertThat(api.post("/api/v1/provider/coupon-contributions",
                Map.of("template_id", subsidyId, "total_count", 10), mine.token()).code()).isEqualTo(40400);

        // 登录域：运营令牌调服务者接口 = 没登录（ADR-0012）
        assertThat(api.get("/api/v1/provider/coupon-contributions", admin).code()).isEqualTo(40100);
        assertThat(api.get("/api/v1/admin/coupon-templates", mine.token()).code()).isEqualTo(40100);
    }

    @Test
    @DisplayName("分页与参数边界：page=0 / page_size=101 / 状态越界都是 40001")
    void pagingAndParamBoundaries() {
        String admin = adminToken();
        createProviderCostTemplate(admin, "CP-030", "30.00", "0.00");
        createProviderCostTemplate(admin, "CP-031", "40.00", "0.00");

        assertThat(api.get("/api/v1/admin/coupon-templates?page=0", admin).code()).isEqualTo(40001);
        assertThat(api.get("/api/v1/admin/coupon-templates?page_size=101", admin).code()).isEqualTo(40001);
        assertThat(api.get("/api/v1/admin/coupon-templates?cost_bearer=3", admin).code()).isEqualTo(40001);

        ApiClient.ApiCall paged = api.get("/api/v1/admin/coupon-templates?page=1&page_size=1", admin);
        assertThat(paged.data().path("list")).hasSize(1);
        assertThat(paged.data().path("total").asLong()).isEqualTo(2);
        assertThat(paged.data().path("has_more").asBoolean()).isTrue();
    }

    // ---------------------------------------------------------------- 工具

    /** 建一个服务者成本券模板（默认无门槛、30 天有效）。 */
    private ApiClient.ApiCall createProviderCostTemplate(String admin, String code, String face,
                                                         String minAmount) {
        return api.post("/api/v1/admin/coupon-templates", Map.of(
                "code", code, "name", "测试券 " + code, "face_value", face, "min_amount", minAmount,
                "valid_days", 30, "cost_bearer", 1), admin);
    }

    /** 建一个平台补贴券模板（金额是字符串，这里用 {@link Price} 校验一下格式与种子一致）。 */
    private long createPlatformSubsidyTemplate(String admin, String code, String face, Integer issueLimit) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("code", code);
        body.put("name", "平台补贴券 " + code);
        body.put("face_value", Price.format(new BigDecimal(face)));
        body.put("min_amount", "0.00");
        body.put("valid_days", 30);
        body.put("cost_bearer", 2);
        if (issueLimit != null) {
            body.put("issue_limit", issueLimit);
        }
        ApiClient.ApiCall created = api.post("/api/v1/admin/coupon-templates", body, admin);
        assertCodeOk(created, "建平台补贴券模板");
        return created.data().path("id").asLong();
    }
}
