package com.pethealth.boot.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethealth.boot.support.ApiClient;
import com.pethealth.boot.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 查询参数的边界（2026-09-28 深测轮）。
 *
 * <p>为什么单独一组：**分页与筛选参数是唯一「客户端说了算、且直接进 SQL LIMIT」的输入**。
 * 它们出问题的形态不是报错，而是「看起来正常的错误答案」——`page=0` 回显成 0、
 * `page_size=0` 拼出 `LIMIT 0` 返回空列表、超上限被静默截断。
 * 上一轮已经修过 page/page_size（测试报告 D20），这一组把**同一个控制器里剩下的两个参数**补齐：
 * `kind` 与 `highlights` 的 `limit`——契约（contract/app.yaml）对它们都写了取值范围，
 * 而实现里没有校验，属同一个「契约与实现对账」的口径。
 *
 * <p>另一条跨用例的检查：**错误响应也要带 `request_id`**。用户来报错时，
 * 我们能靠它把日志翻出来；只在成功响应里带，等于在最需要它的场合没有它。
 */
@DisplayName("查询参数边界（分页 / 筛选 / 错误响应可追溯）")
class QueryBoundaryTest extends IntegrationTestBase {

    private static final String PHONE = "13900009101";

    @Autowired
    private ObjectMapper objectMapper;

    private ApiClient api;

    @BeforeEach
    void setUpClient() {
        api = new ApiClient(rest, objectMapper);
    }

    // ------------------------------------------------------------ 分页（上一轮已修，这里钉回归）

    @Test
    @DisplayName("page / page_size 的上下界都拦：0 与超上限都是 40001，上限内的正常")
    void paginationBoundsAreEnforced() {
        String token = api.registerAndGetAccessToken(PHONE);

        assertThat(api.get("/api/v1/app/messages?page=0", token).code()).isEqualTo(40001);
        assertThat(api.get("/api/v1/app/messages?page_size=0", token).code()).isEqualTo(40001);
        assertThat(api.get("/api/v1/app/messages?page_size=101", token).code()).isEqualTo(40001);
        assertThat(api.get("/api/v1/app/messages?page=-1", token).code()).isEqualTo(40001);

        // 上限内的取值必须放行：把边界拦成 500 或拦成「全部拒绝」都是另一种错
        ApiClient.ApiCall ok = api.get("/api/v1/app/messages?page=1&page_size=100", token);
        assertThat(ok.status()).as(ok.body().toPrettyString()).isEqualTo(200);
        assertThat(ok.data().path("page_size").asLong()).isEqualTo(100);
    }

    @Test
    @DisplayName("超出总页数的 page：返回空列表而不是报错，且回显与 has_more 自洽")
    void pageBeyondTotalIsAnEmptyPageNotAnError() {
        String token = api.registerAndGetAccessToken(PHONE);

        ApiClient.ApiCall call = api.get("/api/v1/app/messages?page=99999&page_size=20", token);

        assertThat(call.status()).as(call.body().toPrettyString()).isEqualTo(200);
        assertThat(call.data().path("list")).isEmpty();
        assertThat(call.data().path("page").asLong()).as("页码要原样回显，前端靠它算下一页").isEqualTo(99999);
        assertThat(call.data().path("has_more").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("分页参数不是数字：40001（不是 500，也不是静默取默认值）")
    void nonNumericPaginationIsRejected() {
        String token = api.registerAndGetAccessToken(PHONE);

        ApiClient.ApiCall call = api.get("/api/v1/app/messages?page_size=abc", token);

        assertThat(call.status()).isEqualTo(400);
        assertThat(call.code()).isEqualTo(40001);
        assertThat(call.requestId()).as("参数错误也要能追溯").isNotBlank();
    }

    // ------------------------------------------------------------ 筛选参数（契约有范围，实现没有）

    @Test
    @DisplayName("kind 只允许契约里的 1 / 2：越界是 40001（现在会静默返回空列表）")
    void messageKindIsLimitedToTheDocumentedEnum() {
        String token = api.registerAndGetAccessToken(PHONE);

        // 契约：schema: { type: integer, enum: [1, 2] }
        assertThat(api.get("/api/v1/app/messages?kind=99", token).code()).as("越界值应当被拒").isEqualTo(40001);
        assertThat(api.get("/api/v1/app/messages?kind=0", token).code()).isEqualTo(40001);
        // 合法取值照常
        assertThat(api.get("/api/v1/app/messages?kind=1", token).status()).isEqualTo(200);
        assertThat(api.get("/api/v1/app/messages?kind=2", token).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("highlights 的 limit 只允许契约里的 1–10：越界是 40001（现在被静默 clamp）")
    void highlightsLimitIsLimitedToTheDocumentedRange() {
        String token = api.registerAndGetAccessToken(PHONE);

        // 契约：schema: { type: integer, minimum: 1, maximum: 10, default: 6 }
        // 现状是 Math.max(1, Math.min(limit, 10)) 静默夹取——与 page=0 被回显成 0 是同一类
        // 「看起来正常但答案不是你要的」。
        assertThat(api.get("/api/v1/app/messages/highlights?limit=99", token).code()).isEqualTo(40001);
        assertThat(api.get("/api/v1/app/messages/highlights?limit=0", token).code()).isEqualTo(40001);
        assertThat(api.get("/api/v1/app/messages/highlights?limit=-1", token).code()).isEqualTo(40001);
        assertThat(api.get("/api/v1/app/messages/highlights?limit=10", token).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("撤销打卡时 category 越界：与提交路径口径一致（提交是 1–6）")
    void undoCategorySharesTheSameRangeAsSubmit() {
        String token = api.registerAndGetAccessToken(PHONE);
        long petId = api.createPet(token, "豆豆");

        // 提交路径：category 有 @Min(1) @Max(6)（D1 那一轮加的）
        ApiClient.ApiCall submit = api.post("/api/v1/app/pets/" + petId + "/check-ins",
                java.util.Map.of("date", today(),
                        "items", java.util.List.of(java.util.Map.of("category", 99, "value", "normal"))), token);
        assertThat(submit.code()).as("提交路径越界一律 40001").isEqualTo(40001);

        // 撤销路径：同一个 category 参数，没有范围校验——两个入口对同一个概念用两套口径
        ApiClient.ApiCall undo = api.delete(
                "/api/v1/app/pets/" + petId + "/check-ins/item?date=" + today() + "&category=99", token);
        assertThat(undo.code()).as("撤销路径应当与提交路径同口径：" + undo.body()).isEqualTo(40001);
    }

    private static String today() {
        return com.pethealth.common.time.AppTime.today().toString();
    }
}
