package com.pethealth.boot.provider;

import com.pethealth.api.provider.OnboardingApplicationRequest;
import com.pethealth.api.provider.ProviderQualificationRequest;
import com.pethealth.boot.support.ApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 服务者联盟分类（一期验收标准的「服务者联盟分类：分类维度维护与归属」）。
 *
 * <p>改造前 {@code provider.category} 是一个只在提交入驻申请时写一次的列（注解上写死 1–3），
 * 运营既加不了档、也改不了任何门店的归属。本类钉住改造后的四条口径：
 *
 * <ul>
 *   <li><b>值域来自表</b>：运营新增一档之后**立刻可指派**（服务端查表校验，不是注解限范围）；
 *   <li><b>停用不移动既有归属</b>：停用只挡住新的指派，已在档上的门店照旧返回该维度名；
 *   <li><b>归属变更进审核流水</b>（{@code action = 9}），与冻结 / 解冻同一张 append-only 表；
 *   <li><b>编码不可改</b>：修改路径忽略 {@code code}，前端传了别的值也不会换掉稳定标识。
 * </ul>
 */
class AllianceCategoryTest extends ProviderApiTestSupport {

    private static final String CATEGORIES = "/api/v1/admin/alliance-categories";

    @Test
    @DisplayName("维度列表：种子三档齐全，id 就是 provider.category 的取值，带当前归属数")
    void listSeededCategories() {
        ApiClient.ApiCall listed = api.get(CATEGORIES, adminToken());
        assertCodeOk(listed, "维度列表");

        assertThat(listed.data()).hasSize(3);
        assertThat(listed.data().get(0).path("id").asInt()).isEqualTo(1);
        assertThat(listed.data().get(0).path("code").asText()).isEqualTo("DIRECT_PEER");
        assertThat(listed.data().get(0).path("name").asText()).isEqualTo("直接同业");
        assertThat(listed.data().get(0).path("enabled").asInt()).isEqualTo(1);
        // 没有门店时归属数是 0（不是 null）——运营要能拿它直接做减法的读数
        assertThat(listed.data().get(0).path("provider_count").asLong()).isZero();
    }

    @Test
    @DisplayName("新增一档维度后**立刻可指派**：不用发版、不用改契约")
    void newCategoryIsImmediatelyAssignable() {
        String admin = adminToken();
        ApiClient.ApiCall created = api.post(CATEGORIES, Map.of(
                "code", "TEST_VET_GROUP", "name", "测试连锁集团", "description", "用例造的维度", "sort_order", 9), admin);
        assertCodeOk(created, "新建维度");
        int newId = created.data().path("id").asInt();
        assertThat(newId).isGreaterThan(3);
        assertThat(created.data().path("enabled").asInt()).isEqualTo(1);

        // 用这一档提交入驻申请：服务端查表放行（写死 1–3 的那个版本会在这里 40001）
        ProviderActor actor = providerActor();
        ApiClient.ApiCall submitted = api.post("/api/v1/provider/onboarding/applications",
                applicationWithCategory(actor.token(), "测试连锁门店", newId, "LIC-NEW-DIM-001"), actor.token());
        assertCodeOk(submitted, "带新维度提交申请");
        assertThat(submitted.data().path("provider").path("category").asInt()).isEqualTo(newId);
        // 名称由服务端查表给出：前端不必持有一份标签表（运营改了名字立刻可见）
        assertThat(submitted.data().path("provider").path("category_name").asText()).isEqualTo("测试连锁集团");
    }

    @Test
    @DisplayName("停用一档：不能再被指派（40001），但既有归属不受影响")
    void disabledCategoryRejectsNewAssignmentButKeepsExisting() {
        String admin = adminToken();
        ApiClient.ApiCall created = api.post(CATEGORIES,
                Map.of("code", "TEST_STOPPED", "name", "测试待停用维度"), admin);
        assertCodeOk(created, "新建维度");
        int categoryId = created.data().path("id").asInt();

        // 先归属一家门店，再停用
        ApprovedProvider provider = createApprovedProvider("LIC-ALLIANCE-STOP-001");
        ApiClient.ApiCall assigned = api.put("/api/v1/admin/providers/" + provider.providerId() + "/alliance",
                Map.of("category", categoryId), admin);
        assertCodeOk(assigned, "指派归属");
        assertThat(assigned.data().path("category_name").asText()).isEqualTo("测试待停用维度");

        ApiClient.ApiCall stopped = api.put(CATEGORIES + "/" + categoryId + "/status",
                Map.of("enabled", 0), admin);
        assertCodeOk(stopped, "停用维度");
        assertThat(stopped.data().path("enabled").asInt()).isZero();
        // 停用一档的连锁反应要看得见：这一档还挂着几家
        assertThat(stopped.data().path("provider_count").asLong()).isEqualTo(1);

        // 既有归属不动：门店详情照旧给出这一档的名字
        ApiClient.ApiCall detail = api.get("/api/v1/admin/providers/" + provider.providerId(), admin);
        assertCodeOk(detail, "门店详情");
        assertThat(detail.data().path("category").asInt()).isEqualTo(categoryId);
        assertThat(detail.data().path("category_name").asText()).isEqualTo("测试待停用维度");

        // 新指派被拒：停用档不能作为归属
        ApprovedProvider other = createApprovedProvider("LIC-ALLIANCE-STOP-002");
        ApiClient.ApiCall rejected = api.put("/api/v1/admin/providers/" + other.providerId() + "/alliance",
                Map.of("category", categoryId), admin);
        assertThat(rejected.code()).isEqualTo(40001);
        assertThat(rejected.message()).contains("已停用");

        // 入驻申请走的是同一条校验：带停用档提交也拒
        ProviderActor actor = providerActor();
        ApiClient.ApiCall submitted = api.post("/api/v1/provider/onboarding/applications",
                applicationWithCategory(actor.token(), "测试停用档门店", categoryId, "LIC-STOPPED-003"), actor.token());
        assertThat(submitted.code()).isEqualTo(40001);
    }

    @Test
    @DisplayName("改归属：写审核流水（action = 9），重复指派同一档返回 40900")
    void reassignWritesReviewLogAndRejectsNoChange() {
        String admin = adminToken();
        ApprovedProvider provider = createApprovedProvider("LIC-ALLIANCE-MOVE-001");

        ApiClient.ApiCall moved = api.put("/api/v1/admin/providers/" + provider.providerId() + "/alliance",
                Map.of("category", 2), admin);
        assertCodeOk(moved, "改归属");
        assertThat(moved.data().path("category").asInt()).isEqualTo(2);
        assertThat(moved.data().path("category_name").asText()).isEqualTo("直接异业");

        // 归属变更进审核流水：与冻结 / 解冻同一张表，事后查得到是谁改的。
        // 这家店是 createApprovedProvider 造的，所以流水里还有「提交」与「审核通过」两条——
        // 这里按 action 过滤出归属那一条（流水是 append-only，前面的行本来就该在）
        List<Map<String, Object>> logs = jdbc.queryForList(
                "SELECT `action`, `target_type`, `remark` FROM `provider_review_log` "
                        + "WHERE `provider_id` = ? AND `action` = 9 ORDER BY `id`", provider.providerId());
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).get("target_type")).isEqualTo(4);
        assertThat((String) logs.get(0).get("remark")).contains("直接异业");

        // 已经是这一档了：40900（不是静默成功——「点了一下但什么也没发生」最难排查）
        ApiClient.ApiCall again = api.put("/api/v1/admin/providers/" + provider.providerId() + "/alliance",
                Map.of("category", 2), admin);
        assertThat(again.code()).isEqualTo(40900);
    }

    @Test
    @DisplayName("编辑维度：改名生效、编码不可改（传了别的值也忽略）、名称重复 40900")
    void updateKeepsCodeAndChecksNameUniqueness() {
        String admin = adminToken();
        ApiClient.ApiCall created = api.post(CATEGORIES,
                Map.of("code", "TEST_RENAME", "name", "测试旧名"), admin);
        assertCodeOk(created, "新建维度");
        int id = created.data().path("id").asInt();

        ApiClient.ApiCall renamed = api.put(CATEGORIES + "/" + id,
                Map.of("code", "TEST_OTHER_CODE", "name", "测试新名", "sort_order", 7), admin);
        assertCodeOk(renamed, "改名");
        assertThat(renamed.data().path("name").asText()).isEqualTo("测试新名");
        assertThat(renamed.data().path("sort_order").asInt()).isEqualTo(7);
        // 编码是稳定标识：改它等于换一档维度，挂在上面的门店不会跟着走
        assertThat(renamed.data().path("code").asText()).isEqualTo("TEST_RENAME");

        ApiClient.ApiCall clash = api.put(CATEGORIES + "/" + id,
                Map.of("code", "TEST_RENAME", "name", "直接同业"), admin);
        assertThat(clash.code()).isEqualTo(40900);

        ApiClient.ApiCall missing = api.put(CATEGORIES + "/999999",
                Map.of("code", "TEST_RENAME", "name", "测试新名"), admin);
        assertThat(missing.code()).isEqualTo(40400);
    }

    @Test
    @DisplayName("越权：服务者令牌读 / 改维度与归属一律 40100（登录域不是 admin）")
    void providerTokenCannotTouchAlliance() {
        ProviderActor actor = providerActor();
        ApiClient.ApiCall list = api.get(CATEGORIES, actor.token());
        assertThat(list.code()).isEqualTo(40100);

        ApprovedProvider provider = createApprovedProvider("LIC-ALLIANCE-AUTH-001");
        ApiClient.ApiCall assign = api.put("/api/v1/admin/providers/" + provider.providerId() + "/alliance",
                Map.of("category", 1), actor.token());
        assertThat(assign.code()).isEqualTo(40100);
    }

    // ---------------------------------------------------------------- 工具

    /** 带指定联盟分类提交入驻申请（目录的种子分类不用动，这里只要多带一个维度取值）。 */
    private OnboardingApplicationRequest applicationWithCategory(String token, String name, int category,
                                                                String licenseNo) {
        return new OnboardingApplicationRequest(
                name, 1, category, null, "测试门店", "上海市徐汇区测试路 1 号", "121.4", "31.2",
                "13800001111", "张三",
                // 营业执照号必须是可见 ASCII（证件号有字符集校验，中文门店名不能进这个字段）
                List.of(new ProviderQualificationRequest(1, "营业执照", licenseNo,
                        uploadQualificationImage(token), null, null)));
    }
}
