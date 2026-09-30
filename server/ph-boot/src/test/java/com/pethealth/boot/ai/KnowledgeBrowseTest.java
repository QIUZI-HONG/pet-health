package com.pethealth.boot.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethealth.boot.support.ApiClient;
import com.pethealth.boot.support.IntegrationTestBase;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 知识库浏览（F024 / F025）的 Java 侧：**透传 + 字段收口**。
 *
 * <p>知识条目只存在 AI 服务那边（ADR-0009），所以这一层的职责就两件：把请求送过去、
 * 把回来的字段收成 C 端该看的样子（`risk_hint` → 1/2/3；信心度这类内部字段不下发）。
 * AI 侧用 JDK HttpServer 打桩（与 {@code AiConsultTest} 同一手法）——真读知识库的行为在
 * `ai/tests/test_knowledge.py` 与 `test_knowledge_db.py` 里验。
 *
 * <p>四条：列表（分类名 + 复核状态 + 列表无正文）、详情（有正文 + 来源）、不可读编号 40400、
 * 需要登录；外加一条降级（AI 侧读不到 → 空列表而不是报错）。
 */
@DisplayName("知识库浏览（F024 / F025）")
class KnowledgeBrowseTest extends IntegrationTestBase {

    private static final String PHONE = "13900005001";
    private static final HttpServer STUB = startStub();

    private static final AtomicReference<String> RESPONDER = new AtomicReference<>(listResponse());
    private static final List<String> RECEIVED = new ArrayList<>();

    @DynamicPropertySource
    static void aiServiceProperties(DynamicPropertyRegistry registry) {
        registry.add("ai.service.base-url", () -> "http://127.0.0.1:" + STUB.getAddress().getPort());
        registry.add("ai.service.token", () -> "integration-test-internal-token");
        registry.add("ai.service.timeout-ms", () -> "3000");
    }

    @Autowired
    private ObjectMapper objectMapper;

    private ApiClient api;

    @BeforeEach
    void setUpClient() {
        api = new ApiClient(rest, objectMapper);
        RESPONDER.set(listResponse());
        RECEIVED.clear();
    }

    @Test
    @DisplayName("列表：分类名与复核状态一并下发，**正文不下发**（点进去才有）")
    void listHidesBodyAndKeepsReviewStatus() {
        String token = api.registerAndGetAccessToken(PHONE);
        RECEIVED.clear();

        ApiClient.ApiCall call = api.get("/api/v1/app/knowledge/entries?category_code=triage", token);

        assertThat(call.code()).as(call.body().toPrettyString()).isZero();
        var row = call.data().path("list").get(0);
        assertThat(row.path("code").asText()).isEqualTo("K-0011");
        assertThat(row.path("category_name").asText()).isEqualTo("症状分诊");
        // 复核状态必须下发：C 端要据此标「未经兽医复核」（ADR-0033）
        assertThat(row.path("review_status").asText()).isEqualTo("pending_review");
        assertThat(row.path("body").asText()).isEmpty();
        // risk_hint（知识侧口径）翻成 1/2/3（C 端口径）
        assertThat(row.path("risk_level").asInt()).isEqualTo(2);
        assertThat(call.data().path("total").asLong()).isEqualTo(1);
        // 分类筛选传给了 AI 侧
        assertThat(RECEIVED.get(0)).contains("triage");
    }

    @Test
    @DisplayName("详情：带正文与来源；不存在（或不可读）的编号回 40400")
    void detailCarriesBody() {
        String token = api.registerAndGetAccessToken(PHONE);
        RESPONDER.set(detailResponse());

        ApiClient.ApiCall call = api.get("/api/v1/app/knowledge/entries/K-0011", token);

        assertThat(call.code()).isZero();
        assertThat(call.data().path("body").asText()).contains("记录次数");
        assertThat(call.data().path("source_title").asText()).isEqualTo("合作兽医审核稿（工程整理，待复核）");

        // 空结果 = 读不到这一条：与「看不到」同码，免得用编号探测哪些条目存在过
        RESPONDER.set("{\"items\":[],\"total\":0,\"knowledge_check\":\"ok\"}");
        assertThat(api.get("/api/v1/app/knowledge/entries/K-9999", token).code()).isEqualTo(40400);
    }

    @Test
    @DisplayName("AI 侧读不到知识库：空列表 + 200，而不是报错（ADR-0026 的降级口径）")
    void unreadableKnowledgeDegrades() {
        String token = api.registerAndGetAccessToken(PHONE);
        RESPONDER.set("{\"items\":[],\"total\":0,\"knowledge_check\":\"unavailable\"}");

        ApiClient.ApiCall call = api.get("/api/v1/app/knowledge/entries", token);

        assertThat(call.status()).isEqualTo(200);
        assertThat(call.data().path("total").asLong()).isZero();
        assertThat(call.data().path("list")).isEmpty();
    }

    @Test
    @DisplayName("需要登录；参数越界回 40001")
    void requiresLoginAndValidParams() {
        String token = api.registerAndGetAccessToken(PHONE);

        assertThat(api.get("/api/v1/app/knowledge/entries", null).code()).isEqualTo(40100);
        assertThat(api.get("/api/v1/app/knowledge/entries?page=0", token).code()).isEqualTo(40001);
        assertThat(api.get("/api/v1/app/knowledge/entries?page_size=101", token).code()).isEqualTo(40001);
        assertThat(api.get("/api/v1/app/knowledge/entries?keyword=" + "长".repeat(65), token).code()).isEqualTo(40001);
    }

    // ---------------------------------------------------------------- 打桩

    private static String listResponse() {
        return """
                {"items":[{"code":"K-0011","title":"犬猫腹泻的家庭观察要点","summary":"注意大便性状与颜色",
                           "body":"","category_code":"triage","category_name":"症状分诊","risk_hint":"yellow",
                           "review_status":"pending_review","source_title":"合作兽医审核稿（工程整理，待复核）",
                           "source_url":null}],"total":1,"knowledge_check":"ok"}
                """;
    }

    private static String detailResponse() {
        return """
                {"items":[{"code":"K-0011","title":"犬猫腹泻的家庭观察要点","summary":"注意大便性状与颜色",
                           "body":"腹泻首先要看性状与颜色，并记录次数。记录次数、性状与时间。",
                           "category_code":"triage","category_name":"症状分诊","risk_hint":"yellow",
                           "review_status":"pending_review","source_title":"合作兽医审核稿（工程整理，待复核）",
                           "source_url":null}],"total":1,"knowledge_check":"ok"}
                """;
    }

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/knowledge/browse", exchange -> {
                RECEIVED.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] bytes = RESPONDER.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("打桩 AI 服务起不来", e);
        }
    }
}
