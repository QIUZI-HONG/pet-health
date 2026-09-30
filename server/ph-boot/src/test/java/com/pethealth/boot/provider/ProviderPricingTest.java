package com.pethealth.boot.provider;

import com.pethealth.boot.support.ApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 选品定价与上架（切片 #105，决策见 ADR-0034）。
 *
 * <p>这一层盯的是交付文档 2.5 的验收项 ——「商家定价 **100% 区间校验**」（文档用词）：
 * 区间内成功、越界返回 **90001**（HTTP 200，`message` 就是「价格须在¥X-¥Y之间」），
 * 区间本身不合法则是 40001。另外覆盖：未审核通过不许上架（40300）、越权（40400）、
 * 改价必须重审、再上架不需要重审、分页边界。
 *
 * <p>种子目录里 {@code HE-004 基础体检} 的区间是 **112.00–208.00**（V21 里按市场参考价 ±30% 给的），
 * 用例直接以它为基准，不再自己造目录项——区间校验要验的是「平台给的区间被用上了」，
 * 用种子数据才顺带验证了这条链路。
 */
class ProviderPricingTest extends ProviderApiTestSupport {

    /** 种子目录项「基础体检」的区间（V21）。 */
    private static final String SERVICE_CODE = "HE-004";
    private static final String PRICE_MIN = "112.00";
    private static final String PRICE_MAX = "208.00";

    @Test
    @DisplayName("区间内定价成功：进入待审核，返回体里带着目录侧的名称与区间")
    void priceWithinRangeSucceeds() {
        ApprovedProvider provider = createApprovedProvider("LIC-PRICE-001");

        ApiClient.ApiCall created = createListing(provider.token(), SERVICE_CODE, "150.00");
        assertCodeOk(created, "区间内定价");
        assertThat(created.data().path("status").asInt()).isZero();
        assertThat(created.data().path("service_name").asText()).isEqualTo("基础体检");
        assertThat(created.data().path("category_name").asText()).isEqualTo("医院");
        assertThat(created.data().path("price").asText()).isEqualTo("150.00");
        assertThat(created.data().path("price_min").asText()).isEqualTo(PRICE_MIN);
        assertThat(created.data().path("price_max").asText()).isEqualTo(PRICE_MAX);
        assertThat(created.data().path("price_unit").asText()).isEqualTo("次");
        assertThat(created.data().path("submitted_at").isNull()).isFalse();

        // 边界值也合法（区间含两端）：HE-005 标准体检 280.00–520.00，HE-012 常见病诊疗 140.00–260.00
        assertCodeOk(createListing(provider.token(), "HE-005", "280.00"), "下限");
        assertCodeOk(createListing(provider.token(), "HE-012", "260.00"), "上限");

        // 重复勾选同一项：报冲突，而不是静默改价
        ApiClient.ApiCall duplicated = createListing(provider.token(), SERVICE_CODE, "160.00");
        assertThat(duplicated.code()).isEqualTo(40900);
        assertThat(duplicated.message()).contains("改价");
    }

    @Test
    @DisplayName("价格越界：业务码 90001（HTTP 200）+ 提示区间")
    void priceOutOfRangeIsRejectedWith90001() {
        ApprovedProvider provider = createApprovedProvider("LIC-PRICE-002");

        ApiClient.ApiCall tooLow = createListing(provider.token(), SERVICE_CODE, "99.99");
        assertThat(tooLow.status()).isEqualTo(200);
        assertThat(tooLow.code()).isEqualTo(90001);
        assertThat(tooLow.message()).isEqualTo("价格须在¥112.00-¥208.00之间");

        ApiClient.ApiCall tooHigh = createListing(provider.token(), SERVICE_CODE, "208.01");
        assertThat(tooHigh.code()).isEqualTo(90001);
        assertThat(tooHigh.message()).contains("112.00").contains("208.00");

        // 越界的定价一条都不许落库（100% 校验 = 没有任何一条越界的行）
        Long stored = jdbc.queryForObject("SELECT COUNT(*) FROM provider_service", Long.class);
        assertThat(stored).isZero();
    }

    @Test
    @DisplayName("价格本身不合法（多于两位小数 / 0 / 非数字）一律 40001")
    void malformedPriceIsParameterError() {
        ApprovedProvider provider = createApprovedProvider("LIC-PRICE-003");

        assertThat(createListing(provider.token(), SERVICE_CODE, "150.005").code()).isEqualTo(40001);
        assertThat(createListing(provider.token(), SERVICE_CODE, "0.00").code()).isEqualTo(40001);
        assertThat(createListing(provider.token(), SERVICE_CODE, "150元").code()).isEqualTo(40001);
        assertThat(createListing(provider.token(), SERVICE_CODE, "-150.00").code()).isEqualTo(40001);
    }

    @Test
    @DisplayName("目录项不存在或已停用：40400（与「价定高了」是不同的结果）")
    void unknownOrDisabledCatalogItemIsNotFound() {
        String admin = adminToken();
        ApprovedProvider provider = createApprovedProvider("LIC-PRICE-004");

        ApiClient.ApiCall unknown = createListing(provider.token(), "HE-999", "150.00");
        assertThat(unknown.code()).isEqualTo(40400);
        assertThat(unknown.message()).contains("HE-999");

        // 平台停用目录项之后，新的选品不能再选它（存量不受影响，见 ADR-0034）。
        // 这里自己造一个目录项来停用，而不是动种子数据——种子被改了会影响同 JVM 里的其它测试。
        long itemId = createTestItem(admin, "TX-009", "120.00", "240.00");
        assertCodeOk(createListing(provider.token(), "TX-009", "150.00"), "停用前可以选");
        assertCodeOk(api.put("/api/v1/admin/catalog/items/" + itemId + "/status", Map.of("status", 0), admin),
                "停用");
        assertThat(createListing(provider.token(), "TX-009", "150.00").code()).isEqualTo(40400);

        // 服务者侧的目录读取也看不到它了
        ApiClient.ApiCall providerCatalog = api.get("/api/v1/provider/catalog/items?keyword=自动生成测试项目",
                provider.token());
        assertThat(providerCatalog.data().path("total").asLong()).isZero();
    }

    @Test
    @DisplayName("未审核通过不许上架：待审核与已驳回都拦（40300），通过后上架 / 下架 / 再上架都通")
    void listingRequiresReviewBeforeGoingLive() {
        String admin = adminToken();
        ApprovedProvider provider = createApprovedProvider("LIC-PRICE-005");
        ApiClient.ApiCall created = createListing(provider.token(), SERVICE_CODE, "150.00");
        long listingId = created.data().path("id").asLong();

        // 待审核：上架被拦，且原因说清是「审核中」
        ApiClient.ApiCall beforeReview = api.put("/api/v1/provider/services/" + listingId + "/status",
                Map.of("status", 1), provider.token());
        assertThat(beforeReview.code()).isEqualTo(40300);
        assertThat(beforeReview.message()).contains("审核");

        // 运营驳回 → 上架仍被拦，原因变成「已被驳回」
        assertCodeOk(api.post("/api/v1/admin/service-listings/" + listingId + "/reject",
                Map.of("reason", "价格与门店定位不符"), admin), "驳回");
        ApiClient.ApiCall afterReject = api.put("/api/v1/provider/services/" + listingId + "/status",
                Map.of("status", 1), provider.token());
        assertThat(afterReject.code()).isEqualTo(40300);
        assertThat(afterReject.message()).contains("驳回");

        // 服务者侧看得见驳回原因；改价回到待审核；运营通过 → 已上架
        ApiClient.ApiCall mine = api.get("/api/v1/provider/services", provider.token());
        assertThat(mine.data().path("list").get(0).path("reject_reason").asText()).isEqualTo("价格与门店定位不符");
        ApiClient.ApiCall repriced = api.put("/api/v1/provider/services/" + listingId,
                Map.of("price", "168.00"), provider.token());
        assertThat(repriced.data().path("status").asInt()).isZero();
        assertThat(repriced.data().path("reject_reason").isNull()).isTrue();

        ApiClient.ApiCall approved = api.post("/api/v1/admin/service-listings/" + listingId + "/approve",
                Map.of(), admin);
        assertCodeOk(approved, "上架审核通过");
        assertThat(approved.data().path("status").asInt()).isEqualTo(1);

        // 服务者自己的列表里不带自己的名字；运营队列里带（审核员要知道这是谁家的）
        assertThat(api.get("/api/v1/provider/services", provider.token())
                .data().path("list").get(0).path("provider_name").isNull()).isTrue();
        ApiClient.ApiCall reviewQueue = api.get("/api/v1/admin/service-listings?status=1", admin);
        assertThat(reviewQueue.data().path("list").get(0).path("provider_name").asText())
                .isEqualTo("测试动物医院");

        // 下架 → 再上架（价格没变，不需要重审）
        ApiClient.ApiCall delisted = api.put("/api/v1/provider/services/" + listingId + "/status",
                Map.of("status", 2), provider.token());
        assertThat(delisted.data().path("status").asInt()).isEqualTo(2);
        ApiClient.ApiCall relisted = api.put("/api/v1/provider/services/" + listingId + "/status",
                Map.of("status", 1), provider.token());
        assertThat(relisted.data().path("status").asInt()).isEqualTo(1);

        // 服务者设不了 0（待审核）与 3（已驳回）这两个状态
        assertThat(api.put("/api/v1/provider/services/" + listingId + "/status",
                Map.of("status", 3), provider.token()).code()).isEqualTo(40001);

        // 流水：提交 → 驳回 → 重提(改价) → 通过 → 下架 → 上架（六步全在，能追出每一步）
        Long logs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM provider_review_log WHERE target_type = 2 AND target_id = ?",
                Long.class, listingId);
        assertThat(logs).isEqualTo(6L);
    }

    @Test
    @DisplayName("已上架后改价会回到待审核（价格是对外承诺）")
    void repricingSendsListingBackToReview() {
        String admin = adminToken();
        ApprovedProvider provider = createApprovedProvider("LIC-PRICE-006");
        long listingId = createListing(provider.token(), SERVICE_CODE, "150.00").data().path("id").asLong();
        assertCodeOk(api.post("/api/v1/admin/service-listings/" + listingId + "/approve", Map.of(), admin),
                "通过");
        assertThat(jdbc.queryForObject("SELECT status FROM provider_service WHERE id = ?", Integer.class,
                listingId)).isEqualTo(1);

        ApiClient.ApiCall repriced = api.put("/api/v1/provider/services/" + listingId,
                Map.of("price", PRICE_MAX), provider.token());
        assertThat(repriced.data().path("status").asInt()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM provider_service WHERE id = ?", Integer.class,
                listingId)).isZero();

        // 改价同样受区间约束
        assertThat(api.put("/api/v1/provider/services/" + listingId, Map.of("price", "500.00"),
                provider.token()).code()).isEqualTo(90001);
    }

    @Test
    @DisplayName("越权：服务者 B 改不了服务者 A 的服务项（40400），但可以自己勾选同一目录项")
    void otherProviderCannotTouchMyListing() {
        ApprovedProvider first = createApprovedProvider("LIC-PRICE-007");
        ApiClient.ApiCall created = createListing(first.token(), SERVICE_CODE, "150.00");
        long listingId = created.data().path("id").asLong();

        ApprovedProvider second = createApprovedProvider("LIC-PRICE-008");
        assertThat(api.put("/api/v1/provider/services/" + listingId, Map.of("price", "120.00"),
                second.token()).code()).isEqualTo(40400);
        assertThat(api.put("/api/v1/provider/services/" + listingId + "/status", Map.of("status", 2),
                second.token()).code()).isEqualTo(40400);

        // 第二家自己勾选同一目录项是合法的：一条「服务者 × 目录项」一条记录
        ApiClient.ApiCall secondCreated = createListing(second.token(), SERVICE_CODE, "130.00");
        assertCodeOk(secondCreated, "第二家选品");
        assertThat(secondCreated.data().path("id").asLong()).isNotEqualTo(listingId);

        // 各自只看得到自己的
        ApiClient.ApiCall secondList = api.get("/api/v1/provider/services", second.token());
        assertThat(secondList.data().path("list")).hasSize(1);
        assertThat(secondList.data().path("list").get(0).path("price").asText()).isEqualTo("130.00");
    }

    @Test
    @DisplayName("服务者侧不用 C 端或运营 Token：登录域不匹配一律 40100")
    void wrongDomainTokenIsRejected() {
        ApprovedProvider provider = createApprovedProvider("LIC-PRICE-009");
        String admin = adminToken();

        // 运营 Token 调服务者接口 / 服务者 Token 调运营接口，都等于没登录
        assertThat(api.get("/api/v1/provider/services", admin).code()).isEqualTo(40100);
        assertThat(api.get("/api/v1/admin/service-listings", provider.token()).code()).isEqualTo(40100);
        // 没有 Token 也是 40100
        assertThat(api.get("/api/v1/provider/services", null).code()).isEqualTo(40100);
    }

    @Test
    @DisplayName("分页边界：page_size=101 / page=0 都是 40001，page_size=1 时 has_more 正确")
    void pagingBoundaries() {
        ApprovedProvider provider = createApprovedProvider("LIC-PRICE-010");
        assertCodeOk(createListing(provider.token(), "HE-004", "150.00"), "第一条");
        assertCodeOk(createListing(provider.token(), "HE-005", "300.00"), "第二条");

        assertThat(api.get("/api/v1/provider/services?page=0", provider.token()).code()).isEqualTo(40001);
        assertThat(api.get("/api/v1/provider/services?page_size=101", provider.token()).code()).isEqualTo(40001);
        assertThat(api.get("/api/v1/provider/services?status=9", provider.token()).code()).isEqualTo(40001);

        ApiClient.ApiCall paged = api.get("/api/v1/provider/services?page=1&page_size=1",
                provider.token());
        assertCodeOk(paged, "第一页");
        assertThat(paged.data().path("list")).hasSize(1);
        assertThat(paged.data().path("total").asLong()).isEqualTo(2);
        assertThat(paged.data().path("has_more").asBoolean()).isTrue();

        // 按状态过滤（都是待审核）
        ApiClient.ApiCall pending = api.get("/api/v1/provider/services?status=0", provider.token());
        assertThat(pending.data().path("total").asLong()).isEqualTo(2);
        ApiClient.ApiCall listed = api.get("/api/v1/provider/services?status=1", provider.token());
        assertThat(listed.data().path("total").asLong()).isZero();
    }

    /** 造一个测试用的目录项（编码 TX-*、分类 TESTDIR），返回它的 id。 */
    private long createTestItem(String admin, String code, String priceMin, String priceMax) {
        ApiClient.ApiCall category = api.post("/api/v1/admin/catalog/categories",
                Map.of("code", "TESTDIR", "item_code_prefix", "TX", "name", "测试分类"), admin);
        if (category.code() != 0 && !category.message().contains("已存在")) {
            throw new AssertionError("建测试分类失败：" + category.body());
        }
        ApiClient.ApiCall item = api.post("/api/v1/admin/catalog/items", Map.of(
                "code", code,
                "category_code", "TESTDIR",
                "name", "自动生成测试项目",
                "price_min", priceMin,
                "price_max", priceMax,
                "price_unit", "次"), admin);
        assertCodeOk(item, "建测试目录项");
        return item.data().path("id").asLong();
    }
}
