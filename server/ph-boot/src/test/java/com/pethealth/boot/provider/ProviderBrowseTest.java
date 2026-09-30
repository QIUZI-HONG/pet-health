package com.pethealth.boot.provider;

import com.pethealth.api.admin.ProviderStatusRequest;
import com.pethealth.boot.support.ApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C 端服务者浏览（找店 / 看店）——契约 contract/app.yaml 的 {@code /providers} 两条。
 *
 * <p>这一层盯的是契约里那三条贯穿口径，每一条都有对应的用例：
 *
 * <ol>
 *   <li><b>只读、不需要身份</b>（ADR-0037 第一节）：匿名能看，带令牌结果一样；
 *   <li><b>可见性口径只有一个</b>：未过审 / 已驳回 / 已冻结 / 资质全部过期一律 40400，
 *       「看不到」与「不存在」对外是同一件事——顺带钉住「资质过期的店不出现在列表里」
 *       （否则用户会被引到一个下不了单的详情页）；
 *   <li><b>不含敏感字段</b>：联系电话脱敏、资质**根本没有证件编号字段**、
 *       列表里没有 status / level / monthly_score 这些内部字段。
 * </ol>
 *
 * <p>种子目录项 {@code HE-004 基础体检}（区间 112.00–208.00）由 V21 插入，用例以它为基准，
 * 顺带验证「名称与区间来自目录侧」这条链路（价格取服务者定的那个值，不是区间）。
 */
class ProviderBrowseTest extends ProviderApiTestSupport {

    private static final String SERVICE_CODE = "HE-004";
    private static final String SERVICE_NAME = "基础体检";
    private static final String LIST_PATH = "/api/v1/app/providers";

    // ---------------------------------------------------------------- 只读、不需要身份

    @Test
    @DisplayName("匿名可浏览：不带令牌与带令牌拿到同一份数据（只读接口不需要身份）")
    void anonymousSeesTheSameAsLoggedIn() {
        ApprovedProvider provider = createApprovedProvider("LIC-BROWSE-ANON");
        long listingId = listAndApprove(provider, "150.00");

        ApiClient.ApiCall anonymous = api.get(LIST_PATH, null);
        ApiClient.ApiCall withToken = api.get(LIST_PATH, providerToken());

        assertThat(anonymous.status()).as(anonymous.body().toPrettyString()).isEqualTo(200);
        assertThat(anonymous.code()).isZero();
        assertThat(anonymous.data().path("list").size()).isEqualTo(1);
        assertThat(anonymous.data().path("list").get(0).path("id").asLong())
                .isEqualTo(provider.providerId());
        // 带令牌不改结果：这两条接口对身份没有要求
        assertThat(withToken.data().toString()).isEqualTo(anonymous.data().toString());

        // 详情同理
        ApiClient.ApiCall detail = api.get(LIST_PATH + "/" + provider.providerId(), null);
        assertThat(detail.status()).as(detail.body().toPrettyString()).isEqualTo(200);
        assertThat(detail.data().path("services").get(0).path("id").asLong()).isEqualTo(listingId);

        // 未登录拿到的仍然是 200：契约写了 security: []，不是「40100 也算安全」
        assertThat(anonymous.body().path("code").asInt()).isZero();
    }

    @Test
    @DisplayName("免登录只覆盖那两条只读路径：同前缀的号源查询仍然要登录")
    void onlyTheTwoBrowsePathsArePublic() {
        ApprovedProvider provider = createApprovedProvider("LIC-BROWSE-SCOPE");

        // 同前缀的号源查询（ph-order 的接口）必须仍然要身份：免登录表是锚定匹配，不是前缀
        ApiClient.ApiCall slots = api.get(
                LIST_PATH + "/" + provider.providerId() + "/appointment-slots?service_id=1&date=2026-10-01", null);
        assertThat(slots.code()).isEqualTo(40100);

        // 列表的子路径（不存在的路径类型）也不该被放行
        ApiClient.ApiCall nested = api.get(LIST_PATH + "/1/anything", null);
        assertThat(nested.code()).isEqualTo(40100);
    }

    @Test
    @DisplayName("浏览器带着过期或伪造的令牌也能浏览：这两条不看身份，令牌无效不该把页面挡死")
    void invalidTokenStillBrowses() {
        ApprovedProvider provider = createApprovedProvider("LIC-BROWSE-BADTOKEN");
        listAndApprove(provider, "150.00");

        // 前端在令牌过期时会先静默刷新，但刷新也可能失败（离线、被吊销）。
        // 那种情况下这两条接口不该回 40100——它们本来就不需要身份。
        ApiClient.ApiCall expired = api.get(LIST_PATH, "not-a-real-token");
        assertThat(expired.status()).as(expired.body().toPrettyString()).isEqualTo(200);
        assertThat(expired.data().path("list").get(0).path("id").asLong()).isEqualTo(provider.providerId());

        // 需要身份的接口仍然拦得住（同一副过期令牌）
        assertThat(api.get("/api/v1/app/pets", "not-a-real-token").code()).isEqualTo(40100);
    }

    // ---------------------------------------------------------------- 列表：谁进得来

    @Test
    @DisplayName("列表只列「可下单」的店：未过审 / 已冻结 / 资质全过期的都不出现")
    void listOnlyBookableProviders() {
        ApprovedProvider bookable = createApprovedProvider("LIC-BROWSE-OK");
        listAndApprove(bookable, "150.00");

        // 已提交但没审核：status=0
        String pendingToken = providerToken();
        ApiClient.ApiCall pending = api.post("/api/v1/provider/onboarding/applications",
                application("待审门店", "13800002222", "LIC-BROWSE-PENDING", null, 1), pendingToken);
        assertCodeOk(pending, "提交待审申请");
        long pendingProviderId = pending.data().path("provider").path("id").asLong();

        // 已审核通过后再冻结：status=3
        ApprovedProvider frozen = createApprovedProvider("LIC-BROWSE-FROZEN");
        ApiClient.ApiCall frozenCall = api.put("/api/v1/admin/providers/" + frozen.providerId() + "/status",
                new ProviderStatusRequest(3, "测试冻结"), adminToken());
        assertCodeOk(frozenCall, "冻结服务者");

        // 资质过期（材料到期日早于今天）：status 仍是 1，但不应出现在列表里
        ApprovedProvider expired = createApprovedProvider("LIC-BROWSE-EXPIRED", LocalDate.now().minusDays(1));

        ApiClient.ApiCall call = api.get(LIST_PATH, null);
        assertThat(call.code()).isZero();
        assertThat(call.data().path("total").asLong()).isEqualTo(1);
        assertThat(call.data().path("list").get(0).path("id").asLong()).isEqualTo(bookable.providerId());

        // 三种不合格的店，详情一律 40400（与「不存在」同码）
        assertThat(api.get(LIST_PATH + "/" + pendingProviderId, null).code()).isEqualTo(40400);
        assertThat(api.get(LIST_PATH + "/" + frozen.providerId(), null).code()).isEqualTo(40400);
        assertThat(api.get(LIST_PATH + "/" + expired.providerId(), null).code()).isEqualTo(40400);
        // 不存在的 id 也是同一句话
        assertThat(api.get(LIST_PATH + "/99999999", null).code()).isEqualTo(40400);
    }

    @Test
    @DisplayName("资质被驳回的店不可浏览：驳回的材料不算资质（与上架门禁同一口径）")
    void rejectedQualificationHidesProvider() {
        ApprovedProvider provider = createApprovedProvider("LIC-BROWSE-REJECTED");
        listAndApprove(provider, "150.00");
        assertThat(api.get(LIST_PATH, null).data().path("total").asLong()).isEqualTo(1);

        // 运营在后续复核里驳回了材料：这一状态目前只能靠库改（补交材料会走 pending）
        jdbc.update("UPDATE `provider_qualification` SET `status` = 2 WHERE `provider_id` = ?",
                provider.providerId());

        assertThat(api.get(LIST_PATH, null).data().path("total").asLong()).isZero();
        assertThat(api.get(LIST_PATH + "/" + provider.providerId(), null).code()).isEqualTo(40400);
    }

    // ---------------------------------------------------------------- 列表：筛、排、分页

    @Test
    @DisplayName("列表：分类筛选 / 关键词只匹配门店名 / 评分降序后按 id 升序 / 分页")
    void listFiltersSortsAndPages() {
        ApprovedProvider hospital = createApprovedProvider("LIC-BROWSE-H1");
        listAndApprove(hospital, "150.00");

        // 另一类（洗护）的门店：分类筛选要能把它包含进来、把医院排除出去
        String groomerToken = providerToken();
        ApiClient.ApiCall groomerCall = api.post("/api/v1/provider/onboarding/applications",
                application("萌宠洗护中心", "13800003333", "LIC-BROWSE-G1", null, 2), groomerToken);
        assertCodeOk(groomerCall, "提交洗护门店");
        long groomerId = groomerCall.data().path("provider").path("id").asLong();
        assertCodeOk(api.post("/api/v1/admin/provider-applications/"
                + groomerCall.data().path("id").asLong() + "/approve", Map.of("remark", "齐全"), adminToken()),
                "审核洗护门店");

        // 分类：type=1 只有医院，type=2 只有洗护
        ApiClient.ApiCall hospitals = api.get(LIST_PATH + "?type=1", null);
        assertThat(hospitals.data().path("total").asLong()).isEqualTo(1);
        assertThat(hospitals.data().path("list").get(0).path("name").asText()).isEqualTo("测试动物医院");

        ApiClient.ApiCall groomers = api.get(LIST_PATH + "?type=2", null);
        assertThat(groomers.data().path("total").asLong()).isEqualTo(1);
        assertThat(groomers.data().path("list").get(0).path("id").asLong()).isEqualTo(groomerId);
        assertThat(groomers.data().path("list").get(0).path("type_name").asText()).isEqualTo("洗护美容");

        // 关键词：匹配门店名（子串）
        assertThat(api.get(LIST_PATH + "?keyword=洗护", null).data().path("total").asLong()).isEqualTo(1);
        // 关键词**不**匹配服务项名：搜「基础体检」不该把医院搜出来（那是详情页的事）
        assertThat(api.get(LIST_PATH + "?keyword=" + SERVICE_NAME, null).data().path("total").asLong())
                .isZero();
        // 认不出的分类码：契约的枚举是 1–6，越界回 40001
        assertThat(api.get(LIST_PATH + "?type=7", null).code()).isEqualTo(40001);
        assertThat(api.get(LIST_PATH + "?type=0", null).code()).isEqualTo(40001);

        // 排序：评分同为 5.0（评价体系未落地），于是稳定按 id 升序——两页之间不能有重复或遗漏
        assertThat(api.get(LIST_PATH + "?page=1&page_size=1", null).data().path("list").get(0)
                .path("id").asLong()).isEqualTo(Math.min(hospital.providerId(), groomerId));
        ApiClient.ApiCall second = api.get(LIST_PATH + "?page=2&page_size=1", null);
        assertThat(second.data().path("list").get(0).path("id").asLong())
                .isEqualTo(Math.max(hospital.providerId(), groomerId));
        assertThat(second.data().path("has_more").asBoolean()).isFalse();
        assertThat(second.data().path("total").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("列表参数越界一律 40001：分类 / 关键词长度 / 页码 / 每页条数")
    void listRejectsBadParams() {
        assertThat(api.get(LIST_PATH + "?keyword=" + "长".repeat(33), null).code()).isEqualTo(40001);
        assertThat(api.get(LIST_PATH + "?page=0", null).code()).isEqualTo(40001);
        assertThat(api.get(LIST_PATH + "?page_size=0", null).code()).isEqualTo(40001);
        assertThat(api.get(LIST_PATH + "?page_size=101", null).code()).isEqualTo(40001);
        // 上限内放行（把边界拦成「全部拒绝」是另一种错）
        assertThat(api.get(LIST_PATH + "?page_size=100", null).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("列表行不含内部字段，联系电话是脱敏值")
    void listRowHidesInternalsAndMasksPhone() {
        ApprovedProvider provider = createApprovedProvider("LIC-BROWSE-FIELDS");
        listAndApprove(provider, "150.00");

        var row = api.get(LIST_PATH, null).data().path("list").get(0);
        assertThat(row.has("status")).as("列表不下发 status：能不能下单只该由服务端说了算").isFalse();
        assertThat(row.has("level")).isFalse();
        assertThat(row.has("monthly_score")).isFalse();
        assertThat(row.path("phone").asText()).isEqualTo("138****1111");
        assertThat(row.path("type_name").asText()).isEqualTo("医院");
        assertThat(row.path("rating").asText()).isNotBlank();
    }

    // ---------------------------------------------------------------- 详情

    @Test
    @DisplayName("详情：在架服务项与价格 / 营业时间 / 资质摘要（不含证照编号）")
    void detailShowsPriceQualificationsAndHours() {
        ApprovedProvider provider = createApprovedProvider("LIC-BROWSE-DETAIL");
        long listedId = listAndApprove(provider, "150.00");
        // 第二个服务项停在待审核：它不该出现在 C 端（点了会 40400 的按钮不能给）
        long pendingListingId = createListing(provider.token(), "HE-005", "280.00").data().path("id").asLong();
        // 营业时间：只列营业的那几天
        assertCodeOk(api.put("/api/v1/provider/profile/business-hours",
                Map.of("hours", List.of(Map.of("day_of_week", 1, "open_time", "09:00", "close_time", "18:00"),
                        Map.of("day_of_week", 6, "open_time", "10:00", "close_time", "16:00"))),
                provider.token()), "设置营业时间");

        ApiClient.ApiCall call = api.get(LIST_PATH + "/" + provider.providerId(), null);
        assertThat(call.code()).as(call.body().toPrettyString()).isZero();
        var data = call.data();

        assertThat(data.path("name").asText()).isEqualTo("测试动物医院");
        assertThat(data.path("type_name").asText()).isEqualTo("医院");
        assertThat(data.path("phone").asText()).isEqualTo("138****1111");
        assertThat(data.path("address").asText()).isEqualTo("上海市徐汇区测试路 1 号");

        // 营业时间：数组里没有的星期几就是休息（不出现 = 不营业）
        assertThat(data.path("business_hours").size()).isEqualTo(2);
        assertThat(data.path("business_hours").get(0).path("day_of_week").asInt()).isEqualTo(1);

        // 服务项：只有已上架的那个；名称 / 单位 / 耗时来自目录侧现取，价格是服务者定的值
        assertThat(data.path("services").size()).isEqualTo(1);
        var service = data.path("services").get(0);
        assertThat(service.path("id").asLong()).isEqualTo(listedId);
        assertThat(service.path("service_code").asText()).isEqualTo(SERVICE_CODE);
        assertThat(service.path("service_name").asText()).isEqualTo(SERVICE_NAME);
        assertThat(service.path("category_name").asText()).isEqualTo("医院");
        assertThat(service.path("price").asText()).isEqualTo("150.00");
        assertThat(service.path("price_unit").asText()).isEqualTo("次");
        assertThat(service.has("duration_minutes")).isTrue();
        assertThat(service.path("status").isMissingNode())
                .as("C 端的服务项没有 status：审核状态是服务者侧的事").isTrue();

        // 资质摘要：有材料、有类型名，**没有证件编号**（不是空值，是没这个字段）
        assertThat(data.path("qualifications").size()).isEqualTo(1);
        var qualification = data.path("qualifications").get(0);
        assertThat(qualification.path("type").asInt()).isEqualTo(1);
        assertThat(qualification.path("type_name").asText()).isEqualTo("营业执照");
        assertThat(qualification.path("name").asText()).isEqualTo("营业执照");
        assertThat(qualification.has("cert_no")).isFalse();
        assertThat(qualification.has("cert_no_masked")).isFalse();
        // 待审核的那个服务项不该出现在 services 里（上面已断言只有 1 条，这里把 id 也钉死）
        assertThat(data.path("services").findValuesAsText("id"))
                .containsExactly(String.valueOf(listedId))
                .doesNotContain(String.valueOf(pendingListingId));
    }

    @Test
    @DisplayName("详情：没有在架服务是正常结果（200 + 空数组），不是错误")
    void detailWithNoListedServiceIsOk() {
        ApprovedProvider provider = createApprovedProvider("LIC-BROWSE-EMPTY");

        ApiClient.ApiCall call = api.get(LIST_PATH + "/" + provider.providerId(), null);
        assertThat(call.status()).isEqualTo(200);
        assertThat(call.code()).isZero();
        assertThat(call.data().path("services").isArray()).isTrue();
        assertThat(call.data().path("services")).isEmpty();
        // 资质照常给：这家店只是没有在架服务，不是不能看
        assertThat(call.data().path("qualifications").size()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- 辅助

    /** 勾选种子目录项并定价，然后由运营审核通过——返回服务项 id（它此后是「在架」）。 */
    private long listAndApprove(ApprovedProvider provider, String price) {
        ApiClient.ApiCall created = createListing(provider.token(), SERVICE_CODE, price);
        assertCodeOk(created, "勾选目录项");
        long listingId = created.data().path("id").asLong();
        assertCodeOk(api.post("/api/v1/admin/service-listings/" + listingId + "/approve",
                Map.of("remark", "价格合规"), adminToken()), "审核通过服务项");
        return listingId;
    }
}
