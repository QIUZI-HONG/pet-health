package com.pethealth.boot.catalog;

import com.pethealth.boot.provider.ProviderApiTestSupport;
import com.pethealth.boot.support.ApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 标准服务目录（切片 #104 的目录部分，决策见 ADR-0034）。
 *
 * <p>覆盖：种子目录的六大类与项目（含编码前缀）、运营的增改、**区间自身的校验**
 * （下限 ≤ 上限、非负、最多两位小数）、编码不可改与不可复用、停用项对服务者不可见且不可定价、
 * 分页边界。
 *
 * <p>为什么继承服务者侧的测试基座：本用例要签「运营域」令牌，而那套签法（后台登录未落地、
 * 直接用 JwtService 签发）只写了一份，放在 {@code com.pethealth.boot.provider} 里共用。
 *
 * <p>清理策略：用例自己造的分类与项目用 {@code TEST*}/{@code TX-*} 前缀，
 * 由基座的清理把它们删掉；**迁移种下的目录不动**——它是整个切片的可见内容。
 */
class CatalogDirectoryTest extends ProviderApiTestSupport {

    @Test
    @DisplayName("种子目录：六大类都在，且带编码前缀与启用项目数")
    void seededCategoriesAndItems() {
        String provider = providerToken();

        ApiClient.ApiCall categories = api.get("/api/v1/provider/catalog/categories", provider);
        assertCodeOk(categories, "目录分类");
        assertThat(categories.data()).hasSize(6);
        assertThat(categories.data().get(0).path("code").asText()).isEqualTo("HOSPITAL");
        assertThat(categories.data().get(0).path("item_code_prefix").asText()).isEqualTo("HE");
        assertThat(categories.data().get(0).path("item_count").asLong()).isPositive();

        // 编码规则：大类两字母 + 三位序号，且前缀与分类一致
        ApiClient.ApiCall items = api.get("/api/v1/provider/catalog/items?category_code=HOSPITAL&page_size=100",
                provider);
        assertCodeOk(items, "目录项");
        assertThat(items.data().path("total").asLong()).isPositive();
        for (var item : items.data().path("list")) {
            assertThat(item.path("code").asText()).startsWith("HE-");
            assertThat(item.path("category_name").asText()).isEqualTo("医院");
            assertThat(item.path("status").asInt()).isEqualTo(1);
            // 区间是字符串（两位小数），不是浮点
            assertThat(item.path("price_min").isTextual()).isTrue();
            assertThat(item.path("price_max").isTextual()).isTrue();
        }
        // 区间按市场参考价 ±30% 给的（基础体检 P=160 → 112.00–208.00）
        ApiClient.ApiCall basicExam = api.get("/api/v1/provider/catalog/items?keyword=基础体检", provider);
        assertThat(basicExam.data().path("total").asLong()).isEqualTo(1);
        assertThat(basicExam.data().path("list").get(0).path("code").asText()).isEqualTo("HE-004");
        assertThat(basicExam.data().path("list").get(0).path("price_min").asText()).isEqualTo("112.00");
        assertThat(basicExam.data().path("list").get(0).path("price_max").asText()).isEqualTo("208.00");
    }

    @Test
    @DisplayName("运营新建分类与目录项，服务者侧立刻看得到")
    void adminCreatesCategoryAndItem() {
        String admin = adminToken();
        assertCodeOk(api.post("/api/v1/admin/catalog/categories",
                Map.of("code", "TESTMED", "item_code_prefix", "TX", "name", "测试医疗",
                        "description", "只用于测试", "sort_order", 99), admin), "建分类");

        ApiClient.ApiCall created = api.post("/api/v1/admin/catalog/items", Map.of(
                "code", "TX-001",
                "category_code", "TESTMED",
                "name", "测试项目一",
                "price_min", "10.00",
                "price_max", "20.00",
                "price_unit", "次",
                "duration_minutes", 30,
                "applicable_pets", 3), admin);
        assertCodeOk(created, "建目录项");
        assertThat(created.data().path("category_name").asText()).isEqualTo("测试医疗");
        assertThat(created.data().path("price_min").asText()).isEqualTo("10.00");
        assertThat(created.data().path("status").asInt()).isEqualTo(1);

        // 服务者侧读得到（且只给启用的）
        String provider = providerToken();
        ApiClient.ApiCall items = api.get("/api/v1/provider/catalog/items?keyword=测试项目一", provider);
        assertThat(items.data().path("total").asLong()).isEqualTo(1);
        assertThat(items.data().path("list").get(0).path("code").asText()).isEqualTo("TX-001");

        // 运营侧看得到停用项（这里先停用再查）
        long itemId = created.data().path("id").asLong();
        assertCodeOk(api.put("/api/v1/admin/catalog/items/" + itemId + "/status", Map.of("status", 0), admin),
                "停用");
        ApiClient.ApiCall adminItems = api.get("/api/v1/admin/catalog/items?status=0&keyword=测试项目一", admin);
        assertThat(adminItems.data().path("total").asLong()).isEqualTo(1);
        ApiClient.ApiCall providerItems = api.get("/api/v1/provider/catalog/items?keyword=测试项目一", provider);
        assertThat(providerItems.data().path("total").asLong()).isZero();
    }

    @Test
    @DisplayName("区间自身要合法：下限 ≤ 上限、非负、最多两位小数（越界是 40001，不是 90001）")
    void invalidRangeIsRejectedAtWriteTime() {
        String admin = adminToken();
        assertCodeOk(api.post("/api/v1/admin/catalog/categories",
                Map.of("code", "TESTMED", "item_code_prefix", "TX", "name", "测试医疗"), admin), "建分类");

        // 下限高于上限：写入口就拦住，否则它会在某个服务者定价时以「越界」的形式暴露，
        // 那时已经分不清是区间错还是价格错
        ApiClient.ApiCall reversed = createItem(admin, "TX-101", "30.00", "20.00");
        assertThat(reversed.code()).isEqualTo(40001);
        assertThat(reversed.message()).contains("下限");

        assertThat(createItem(admin, "TX-102", "-1.00", "20.00").code()).isEqualTo(40001);
        assertThat(createItem(admin, "TX-103", "10.005", "20.00").code()).isEqualTo(40001);
        assertThat(createItem(admin, "TX-104", "0", "20").code()).isZero();

        // 非法区间一条都没落库
        Long stored = jdbc.queryForObject("SELECT COUNT(*) FROM service_item WHERE code IN "
                + "('TX-101','TX-102','TX-103')", Long.class);
        assertThat(stored).isZero();
    }

    @Test
    @DisplayName("编码不可改、分类不可改、编码不可复用")
    void codeAndCategoryAreImmutable() {
        String admin = adminToken();
        assertCodeOk(api.post("/api/v1/admin/catalog/categories",
                Map.of("code", "TESTMED", "item_code_prefix", "TX", "name", "测试医疗"), admin), "建分类");
        long itemId = createItem(admin, "TX-201", "10.00", "20.00").data().path("id").asLong();

        // 编码不可改：订单项与券适用范围都挂在它上面
        ApiClient.ApiCall renamed = api.put("/api/v1/admin/catalog/items/" + itemId, Map.of(
                "code", "TX-202", "category_code", "TESTMED", "name", "改个名",
                "price_min", "10.00", "price_max", "20.00"), admin);
        assertThat(renamed.code()).isEqualTo(40001);
        assertThat(renamed.message()).contains("编码");

        // 分类不可改：编码的前两位就是分类前缀，换分类必然要换码
        ApiClient.ApiCall moved = api.put("/api/v1/admin/catalog/items/" + itemId, Map.of(
                "code", "TX-201", "category_code", "HOSPITAL", "name", "改个名",
                "price_min", "10.00", "price_max", "20.00"), admin);
        assertThat(moved.code()).isEqualTo(40001);
        assertThat(moved.message()).contains("分类");

        // 改名与改区间可以，且区间同样受校验
        assertCodeOk(api.put("/api/v1/admin/catalog/items/" + itemId, Map.of(
                "code", "TX-201", "category_code", "TESTMED", "name", "测试项目（改名）",
                "price_min", "12.00", "price_max", "24.00"), admin), "改名改区间");

        // 编码不可复用：同一编码再建一次 → 40900
        assertThat(createItem(admin, "TX-201", "10.00", "20.00").code()).isEqualTo(40900);
        // 分类编码与编码前缀同样不可复用
        assertThat(api.post("/api/v1/admin/catalog/categories",
                Map.of("code", "TESTMED", "item_code_prefix", "TY", "name", "重名"), admin).code())
                .isEqualTo(40900);
        assertThat(api.post("/api/v1/admin/catalog/categories",
                Map.of("code", "TESTOTHER", "item_code_prefix", "TX", "name", "抢前缀"), admin).code())
                .isEqualTo(40900);
    }

    @Test
    @DisplayName("分类编码与编码前缀不可变更（改了会打断已发布的项目编码）")
    void categoryCodeIsImmutable() {
        String admin = adminToken();
        long categoryId = api.post("/api/v1/admin/catalog/categories",
                        Map.of("code", "TESTMED", "item_code_prefix", "TX", "name", "测试医疗"), admin)
                .data().path("id").asLong();

        assertThat(api.put("/api/v1/admin/catalog/categories/" + categoryId,
                Map.of("code", "TESTRENAMED", "item_code_prefix", "TX", "name", "测试医疗"), admin).code())
                .isEqualTo(40001);
        assertThat(api.put("/api/v1/admin/catalog/categories/" + categoryId,
                Map.of("code", "TESTMED", "item_code_prefix", "TZ", "name", "测试医疗"), admin).code())
                .isEqualTo(40001);
        assertCodeOk(api.put("/api/v1/admin/catalog/categories/" + categoryId,
                Map.of("code", "TESTMED", "item_code_prefix", "TX", "name", "测试医疗（改名）",
                        "sort_order", 98), admin), "改名可以");
        assertThat(api.put("/api/v1/admin/catalog/categories/999999",
                Map.of("code", "TESTMED", "item_code_prefix", "TX", "name", "不存在"), admin).code())
                .isEqualTo(40400);
    }

    @Test
    @DisplayName("未登录 / 域不对：目录接口不能匿名访问")
    void catalogRequiresLogin() {
        assertThat(api.get("/api/v1/provider/catalog/categories", null).code()).isEqualTo(40100);
        assertThat(api.get("/api/v1/admin/catalog/items", null).code()).isEqualTo(40100);
        String provider = providerToken();
        String admin = adminToken();
        assertThat(api.get("/api/v1/admin/catalog/items", provider).code()).isEqualTo(40100);
        assertThat(api.get("/api/v1/provider/catalog/items", admin).code()).isEqualTo(40100);
    }

    @Test
    @DisplayName("分页边界：page=0 / page_size=101 / 关键字超长都是 40001")
    void pagingBoundaries() {
        String admin = adminToken();
        assertThat(api.get("/api/v1/admin/catalog/items?page=0", admin).code()).isEqualTo(40001);
        assertThat(api.get("/api/v1/admin/catalog/items?page_size=101", admin).code()).isEqualTo(40001);
        assertThat(api.get("/api/v1/admin/catalog/items?keyword=" + "长".repeat(65), admin).code())
                .isEqualTo(40001);

        ApiClient.ApiCall page = api.get("/api/v1/admin/catalog/items?page=2&page_size=5", admin);
        assertCodeOk(page, "第二页");
        assertThat(page.data().path("list").size()).isLessThanOrEqualTo(5);
        assertThat(page.data().path("page").asLong()).isEqualTo(2);
        assertThat(page.data().path("page_size").asLong()).isEqualTo(5);
        // 种子里有 40+ 个目录项，所以第二页一定还有内容
        assertThat(page.data().path("total").asLong()).isGreaterThan(10);
        assertThat(page.data().path("has_more").asBoolean()).isTrue();
    }

    private ApiClient.ApiCall createItem(String admin, String code, String min, String max) {
        return api.post("/api/v1/admin/catalog/items", Map.of(
                "code", code,
                "category_code", "TESTMED",
                "name", "测试项目",
                "price_min", min,
                "price_max", max,
                "price_unit", "次"), admin);
    }
}
