package com.pethealth.boot.catalog;

import com.pethealth.boot.provider.ProviderApiTestSupport;
import com.pethealth.boot.support.ApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C 端目录浏览（分类导航 / 目录项 / **按项目找店**）——契约 contract/app.yaml 的 {@code /catalog/**}。
 *
 * <p>四条口径，每条都有用例：
 *
 * <ol>
 *   <li><b>只给启用中的分类与项目</b>：停用是运营的动作，意味着「暂时不对外」；
 *   <li><b>区间价不是门店报价</b>：按项目找店时要给出**这家店的定价**与下单用的 {@code service_id}；
 *   <li><b>可见性口径与 `/providers` 完全一致</b>：未过审 / 已冻结 / 资质过期的门店不出现在
 *       「谁在卖这个项目」里——两条链路各写一遍就会分叉，所以这里刻意各钉一遍；
 *   <li><b>等级即 AI 推荐优先级</b>（交付文档 2.3 / ADR-0052 第三节）：两条列表都按等级优先排序，
 *       同级再按评分与 id——`rating` 未落地时，等级是唯一有意义的区分度。
 * </ol>
 *
 * <p>测试数据用 {@code TEST*} 前缀（分类与项目）与基座的清理策略对齐；**迁移种下的目录不动**。
 */
class CatalogBrowseTest extends ProviderApiTestSupport {

    private static final String CATEGORIES = "/api/v1/app/catalog/categories";
    private static final String ITEMS = "/api/v1/app/catalog/items";

    // ---------------------------------------------------------------- 只读、不需要身份

    @Test
    @DisplayName("三条目录接口都匿名可浏览，带令牌结果一致（只读不需要身份）")
    void anonymousCanBrowseCatalog() {
        ApprovedProvider provider = createApprovedProvider("LIC-CAT-ANON");
        long serviceId = listAndApprove(provider, "HE-004", "150.00");

        for (String path : new String[]{CATEGORIES, ITEMS, ITEMS + "/HE-004/providers"}) {
            ApiClient.ApiCall anonymous = api.get(path, null);
            assertThat(anonymous.status()).as(path).isEqualTo(200);
            assertThat(anonymous.code()).as(path).isZero();
            assertThat(api.get(path, providerToken()).data().toString())
                    .as(path + " 带令牌不该改变结果").isEqualTo(anonymous.data().toString());
        }

        // 按项目找店拿到了下单要用的 id 与门店定价
        var row = api.get(ITEMS + "/HE-004/providers", null).data().path("list").get(0);
        assertThat(row.path("service_id").asLong()).isEqualTo(serviceId);
        assertThat(row.path("price").asText()).isEqualTo("150.00");
        assertThat(row.path("price_unit").asText()).isEqualTo("次");
        assertThat(row.path("phone").asText()).isEqualTo("138****1111");
    }

    // ---------------------------------------------------------------- 分类与项目

    @Test
    @DisplayName("分类导航：种子六类都在、按运营顺序，且只给启用中的分类")
    void categoriesOnlyEnabledInSortOrder() {
        String admin = adminToken();
        createTestCategory(admin, "TESTHIDDEN", "TZ", "测试隐藏分类", 99);
        assertThat(api.get(CATEGORIES, null).data().findValuesAsText("code")).contains("TESTHIDDEN");

        // 停用之后 C 端不该再看到它（运营侧仍然看得到自己停用过什么——那是另一条接口的事）
        jdbc.update("UPDATE `service_category` SET `status` = 0 WHERE `code` = ?", "TESTHIDDEN");

        var codes = api.get(CATEGORIES, null).data().findValuesAsText("code");
        assertThat(codes).contains("HOSPITAL", "GROOMING", "TRAINING", "BOARDING", "SUPPLIES", "INDIRECT");
        assertThat(codes).doesNotContain("TESTHIDDEN");
        // 顺序即运营维护的顺序：医院排第一（种子 sort_order 最小）
        assertThat(codes.get(0)).isEqualTo("HOSPITAL");
        // item_count 只算启用中的项目
        assertThat(api.get(CATEGORIES, null).data().get(0).path("item_count").asLong()).isPositive();
    }

    @Test
    @DisplayName("目录项：只给启用中的项目，支持分类筛选与项目名关键词，分页参数越界 40001")
    void itemsOnlyEnabledFilteredAndPaged() {
        String admin = adminToken();
        createTestCategory(admin, "TESTMED", "TX", "测试医疗", 98);
        long enabledId = createTestItem(admin, "TX-001", "TESTMED", "测试项目一");
        long disabledId = createTestItem(admin, "TX-002", "TESTMED", "测试项目二");
        assertCodeOk(api.put("/api/v1/admin/catalog/items/" + disabledId + "/status",
                Map.of("status", 0), admin), "停用目录项");

        // 分类筛选：只剩启用的那一个
        ApiClient.ApiCall list = api.get(ITEMS + "?category_code=TESTMED", null);
        assertThat(list.data().path("total").asLong()).isEqualTo(1);
        assertThat(list.data().path("list").get(0).path("code").asText()).isEqualTo("TX-001");
        assertThat(list.data().path("list").get(0).path("category_name").asText()).isEqualTo("测试医疗");
        assertThat(list.data().path("list").findValuesAsText("code")).doesNotContain("TX-002");
        assertThat(enabledId).isNotEqualTo(disabledId);

        // 关键词只匹配项目名（分类名不参与）
        assertThat(api.get(ITEMS + "?keyword=测试项目一", null).data().path("total").asLong()).isEqualTo(1);
        assertThat(api.get(ITEMS + "?keyword=测试医疗", null).data().path("total").asLong()).isZero();

        // 分页与边界
        assertThat(api.get(ITEMS + "?page=0", null).code()).isEqualTo(40001);
        assertThat(api.get(ITEMS + "?page_size=101", null).code()).isEqualTo(40001);
        assertThat(api.get(ITEMS + "?keyword=" + "长".repeat(33), null).code()).isEqualTo(40001);
        assertThat(api.get(ITEMS + "?page_size=100", null).status()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- 按项目找店

    @Test
    @DisplayName("按项目找店：只列「可下单 且 这个项目在架」的门店，并给出该店定价")
    void itemProvidersOnlyBookableAndListed() {
        ApprovedProvider selling = createApprovedProvider("LIC-CAT-SELL");
        long sellingServiceId = listAndApprove(selling, "HE-004", "150.00");
        // 另一家店：有资质、也在卖别的项目，但没上架 HE-004
        ApprovedProvider other = createApprovedProvider("LIC-CAT-OTHER");
        listAndApprove(other, "HE-005", "280.00");
        // 第三家：上架了 HE-004 但停在待审核 → 不算「在架」
        ApprovedProvider pending = createApprovedProvider("LIC-CAT-PENDING");
        assertCodeOk(createListing(pending.token(), "HE-004", "160.00"), "勾选目录项（不审核）");

        ApiClient.ApiCall call = api.get(ITEMS + "/HE-004/providers", null);
        assertThat(call.data().path("total").asLong()).isEqualTo(1);
        var row = call.data().path("list").get(0);
        assertThat(row.path("provider_id").asLong()).isEqualTo(selling.providerId());
        assertThat(row.path("service_id").asLong()).isEqualTo(sellingServiceId);
        assertThat(row.path("price").asText()).isEqualTo("150.00");
        assertThat(row.path("type_name").asText()).isEqualTo("医院");
        assertThat(row.path("address").asText()).isNotBlank();

        // 没有门店做的项目：200 + 空列表（不是错误）；目录里确实存在但没人卖
        ApiClient.ApiCall empty = api.get(ITEMS + "/IN-006/providers", null);
        assertThat(empty.status()).isEqualTo(200);
        assertThat(empty.data().path("list")).isEmpty();
        assertThat(empty.data().path("total").asLong()).isZero();
    }

    @Test
    @DisplayName("按项目找店的可见性口径与 /providers 一致：冻结 / 资质过期的门店都不出现")
    void itemProvidersShareVisibilityRule() {
        ApprovedProvider frozen = createApprovedProvider("LIC-CAT-FROZEN");
        listAndApprove(frozen, "HE-004", "150.00");
        ApprovedProvider expired = createApprovedProvider("LIC-CAT-EXPIRED");
        listAndApprove(expired, "HE-004", "150.00");
        ApprovedProvider hidden = createApprovedProvider("LIC-CAT-HIDDEN");
        listAndApprove(hidden, "HE-004", "150.00");

        assertCodeOk(api.put("/api/v1/admin/providers/" + frozen.providerId() + "/status",
                Map.of("status", 3, "reason", "测试冻结"), adminToken()), "冻结");
        // 材料到期日早于今天：门店状态仍是「正常」、存量服务项也还在架上
        // （批算每天 03:30 才自动下架），此刻它必须已经不可浏览——可见性判的是「有没有一份没过期的材料」，
        // 而不是「服务项还在不在架」
        jdbc.update("UPDATE `provider_qualification` SET `valid_until` = ? WHERE `provider_id` = ?",
                java.time.LocalDate.now().minusDays(1), expired.providerId());

        var ids = api.get(ITEMS + "/HE-004/providers", null).data().path("list").findValuesAsText("provider_id");
        assertThat(ids).containsExactly(String.valueOf(hidden.providerId()));
        // 两条链路对同一个门店给同一个答案
        assertThat(api.get("/api/v1/app/providers", null).data().path("list").findValuesAsText("id"))
                .containsExactly(String.valueOf(hidden.providerId()));
    }

    @Test
    @DisplayName("按项目找店：项目不存在 / 已停用 / 已软删一律 40400，与「没有门店做」区分开")
    void itemProvidersRequiresEnabledItem() {
        String admin = adminToken();
        createTestCategory(admin, "TESTGONE", "TQ", "测试已停用分类", 97);
        long itemId = createTestItem(admin, "TQ-100", "TESTGONE", "测试已停用项目");

        assertThat(api.get(ITEMS + "/NO-SUCH/providers", null).code()).isEqualTo(40400);
        assertCodeOk(api.put("/api/v1/admin/catalog/items/" + itemId + "/status",
                Map.of("status", 0), admin), "停用目录项");
        assertThat(api.get(ITEMS + "/TQ-100/providers", null).code()).isEqualTo(40400);

        jdbc.update("UPDATE `service_item` SET `is_deleted` = 1 WHERE `id` = ?", itemId);
        assertThat(api.get(ITEMS + "/TQ-100/providers", null).code()).isEqualTo(40400);
    }

    // ---------------------------------------------------------------- 等级即 AI 推荐优先级

    @Test
    @DisplayName("等级优先排序：战略合作（3）排在基础（1）之前，两条列表口径一致")
    void levelDecidesRecommendationPriority() {
        ApprovedProvider basic = createApprovedProvider("LIC-LV-BASIC");
        listAndApprove(basic, "HE-004", "150.00");
        ApprovedProvider strategic = createApprovedProvider("LIC-LV-STRATEGIC");
        listAndApprove(strategic, "HE-004", "150.00");

        // 先确认默认都是基础级，且按 id 升序（rating 相同的确定性顺序）
        assertThat(api.get("/api/v1/app/providers", null).data().path("list").findValuesAsText("id"))
                .containsExactly(String.valueOf(basic.providerId()), String.valueOf(strategic.providerId()));

        // 考核把后开的那家算成战略合作
        jdbc.update("UPDATE `provider` SET `level` = 3 WHERE `id` = ?", strategic.providerId());

        // 按分类找店与按项目找店，两条列表都该把战略级排到前面
        assertThat(api.get("/api/v1/app/providers", null).data().path("list").findValuesAsText("id"))
                .containsExactly(String.valueOf(strategic.providerId()), String.valueOf(basic.providerId()));
        assertThat(api.get(ITEMS + "/HE-004/providers", null).data().path("list")
                .findValuesAsText("provider_id"))
                .containsExactly(String.valueOf(strategic.providerId()), String.valueOf(basic.providerId()));
    }

    // ---------------------------------------------------------------- 辅助

    private void createTestCategory(String admin, String code, String prefix, String name, int sortOrder) {
        assertCodeOk(api.post("/api/v1/admin/catalog/categories",
                Map.of("code", code, "item_code_prefix", prefix, "name", name,
                        "description", "只用于测试", "sort_order", sortOrder), admin), "建测试分类 " + code);
    }

    private long createTestItem(String admin, String code, String categoryCode, String name) {
        ApiClient.ApiCall created = api.post("/api/v1/admin/catalog/items", Map.of(
                "code", code,
                "category_code", categoryCode,
                "name", name,
                "price_min", "10.00",
                "price_max", "20.00",
                "price_unit", "次",
                "duration_minutes", 30,
                "applicable_pets", 3), admin);
        assertCodeOk(created, "建测试目录项 " + code);
        return created.data().path("id").asLong();
    }

    /** 勾选目录项并定价，再由运营审核通过——返回服务项 id（此后它是「在架」）。 */
    private long listAndApprove(ApprovedProvider provider, String serviceCode, String price) {
        ApiClient.ApiCall created = createListing(provider.token(), serviceCode, price);
        assertCodeOk(created, "勾选 " + serviceCode);
        long listingId = created.data().path("id").asLong();
        assertCodeOk(api.post("/api/v1/admin/service-listings/" + listingId + "/approve",
                Map.of("remark", "价格合规"), adminToken()), "审核通过 " + serviceCode);
        return listingId;
    }
}
