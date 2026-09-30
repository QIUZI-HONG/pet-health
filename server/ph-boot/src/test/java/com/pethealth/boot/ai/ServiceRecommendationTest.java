package com.pethealth.boot.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethealth.boot.support.ApiClient;
import com.pethealth.boot.support.IntegrationTestBase;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
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
 * F011「AI 帮我找服务」的**规则版**闭环（ADR-0050 第二节）。
 *
 * <p>AI 侧用 JDK 自带的 HttpServer 打桩（与 {@code AiConsultTest} 同一手法）：要验的一半在
 * Java 与 Python 的接缝上——症状词怎么传过来、映射表怎么查、降级怎么表达。
 *
 * <p>五条口径，每条一个用例：
 *
 * <ol>
 *   <li>**推荐理由说得出来**：项目来自映射表、区间价来自目录、理由里带着症状原词；
 *   <li>**红色要说「立即就医」**：`risk_hint=red` 的症状必须让 `notice` 带出就医建议；
 *   <li>**认不出不是错**：空结果 + 一句给下一步的话（与 `degraded` 分开）；
 *   <li>**AI 侧读不到知识是降级不是报错**（ADR-0026）：200 + `degraded=true`；
 *   <li>**不落 AI 留痕、不占 AI 额度**：它是「找服务」，不是「咨询」——这一条只有查库能验。
 * </ol>
 */
@DisplayName("AI 帮我找服务（F011 规则版 / ADR-0050 第二节）")
class ServiceRecommendationTest extends IntegrationTestBase {

    private static final String PHONE = "13900003001";

    /** 打桩的 AI 服务：每个用例通过 {@link #RESPONDER} 决定它回什么。 */
    private static final HttpServer STUB = startStub();

    private static final AtomicReference<Responder> RESPONDER =
            new AtomicReference<>(body -> new StubReply(200, matches()));
    private static final List<String> RECEIVED = new ArrayList<>();

    private interface Responder {
        StubReply reply(String requestBody);
    }

    private record StubReply(int status, String body) {
    }

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
        RESPONDER.set(body -> new StubReply(200, matches()));
        RECEIVED.clear();
        resetSeedState();
    }

    @AfterEach
    void resetSeedState() {
        // 本类会用「停用一条映射 / 停用一个项目」来验跳过分支，种子状态必须复位——
        // 同一次运行里别的用例（与目录、定价有关）还要用这些行
        jdbc.update("UPDATE `catalog_symptom_rule` SET `enabled` = 1");
        jdbc.update("UPDATE `service_item` SET `status` = 1 WHERE `code` = 'HE-013'");
    }

    // ------------------------------------------------------------ 主链路

    @Test
    @DisplayName("描述症状 → 认出的症状 + 推荐项目 + 说得出来的理由（区间价来自目录）")
    void recommendsItemsWithExplainableReason() {
        String token = api.registerAndGetAccessToken(PHONE);
        RECEIVED.clear();

        ApiClient.ApiCall call = api.post("/api/v1/app/service-recommendations",
                java.util.Map.of("text", "我家猫今天拉稀两次，精神还行"), token);

        assertThat(call.code()).as(call.body().toPrettyString()).isZero();
        var data = call.data();
        // 症状：规范名 + 分诊条目的就医紧迫程度（yellow → 2）
        assertThat(data.path("matched")).hasSize(1);
        assertThat(data.path("matched").get(0).path("symptom").asText()).isEqualTo("腹泻");
        assertThat(data.path("matched").get(0).path("entry_code").asText()).isEqualTo("K-0011");
        assertThat(data.path("risk_level").asInt()).isEqualTo(2);

        // 推荐：种子映射里腹泻 → 常见病诊疗（主推）+ 专项检查（备选）
        assertThat(data.path("recommendations")).hasSize(2);
        var first = data.path("recommendations").get(0);
        assertThat(first.path("item_code").asText()).isEqualTo("HE-012");
        assertThat(first.path("name").asText()).isEqualTo("常见病诊疗");
        assertThat(first.path("category_name").asText()).isEqualTo("医院");
        // 区间价是目录的，不是门店报价
        assertThat(first.path("price_min").isTextual()).isTrue();
        assertThat(first.path("price_max").isTextual()).isTrue();
        // 理由说得出来：症状原词 + 项目名
        assertThat(first.path("reason").asText()).isEqualTo("因为你说「腹泻」，建议先做常见病诊疗");
        assertThat(data.path("recommendations").get(1).path("item_code").asText()).isEqualTo("HE-013");

        // 免责声明必须带出来（医疗相关硬要求）
        assertThat(data.path("notice").asText()).contains("不是诊断");
        assertThat(data.path("degraded").asBoolean()).isFalse();

        // 传给 AI 侧的是原始描述（归一化在那边做）
        assertThat(RECEIVED).hasSize(1);
        assertThat(RECEIVED.get(0)).contains("拉稀");
    }

    @Test
    @DisplayName("红色症状：notice 必须说「立即就医」（安全文案只有服务端一个来源）")
    void redSymptomForcesEmergencyNotice() {
        String token = api.registerAndGetAccessToken(PHONE);
        RESPONDER.set(body -> new StubReply(200, """
                {"matches":[{"symptom":"呼吸异常","entry_code":"K-0013","title":"咳嗽与呼吸异常的区分",
                             "risk_hint":"red"}],"knowledge_check":"ok"}
                """));

        ApiClient.ApiCall call = api.post("/api/v1/app/service-recommendations",
                java.util.Map.of("text", "它一直张着嘴呼吸，牙龈有点白"), token);

        assertThat(call.code()).isZero();
        assertThat(call.data().path("risk_level").asInt()).isEqualTo(3);
        assertThat(call.data().path("notice").asText()).contains("立即就医");
        // 呼吸异常在种子里映射到专项检查（HE-013）——红色也照常给项目，但话要先说清
        assertThat(call.data().path("recommendations").get(0).path("item_code").asText()).isEqualTo("HE-013");
    }

    // ------------------------------------------------------------ 认不出与降级

    @Test
    @DisplayName("认不出症状：空结果是正常结果，话术给下一步（不是降级）")
    void noSymptomIsNotAnError() {
        String token = api.registerAndGetAccessToken(PHONE);
        RESPONDER.set(body -> new StubReply(200, "{\"matches\":[],\"knowledge_check\":\"ok\"}"));

        ApiClient.ApiCall call = api.post("/api/v1/app/service-recommendations",
                java.util.Map.of("text", "今天天气不错，带它出去玩了"), token);

        assertThat(call.status()).isEqualTo(200);
        assertThat(call.data().path("matched")).isEmpty();
        assertThat(call.data().path("recommendations")).isEmpty();
        assertThat(call.data().path("risk_level").isNull()).isTrue();
        assertThat(call.data().path("degraded").asBoolean()).isFalse();
        assertThat(call.data().path("notice").asText()).contains("没有识别到具体的症状词");
    }

    @Test
    @DisplayName("AI 侧读不到知识 / 服务不可用：一律 200 + degraded，不报错（ADR-0026）")
    void aiUnavailableDegrades() {
        String token = api.registerAndGetAccessToken(PHONE);

        // ① 协议内的降级：AI 侧说自己没读到知识
        RESPONDER.set(body -> new StubReply(200, "{\"matches\":[],\"knowledge_check\":\"unavailable\"}"));
        ApiClient.ApiCall degraded = api.post("/api/v1/app/service-recommendations",
                java.util.Map.of("text", "拉稀两天了"), token);
        assertThat(degraded.status()).isEqualTo(200);
        assertThat(degraded.data().path("degraded").asBoolean()).isTrue();
        assertThat(degraded.data().path("notice").asText()).contains("暂时没能读出描述里的症状");

        // ② 传输层的失败：AI 服务 500（与「认不出」也是同一句降级话术）
        RESPONDER.set(body -> new StubReply(500, "boom"));
        ApiClient.ApiCall broken = api.post("/api/v1/app/service-recommendations",
                java.util.Map.of("text", "拉稀两天了"), token);
        assertThat(broken.status()).isEqualTo(200);
        assertThat(broken.data().path("degraded").asBoolean()).isTrue();
    }

    // ------------------------------------------------------------ 边界

    @Test
    @DisplayName("过期的映射会被跳过：映射被停用、或指向的项目被停用，都只给剩下的推荐")
    void staleMappingIsSkippedWithoutFailing() {
        String token = api.registerAndGetAccessToken(PHONE);

        // ① 运营把「腹泻 → 专项检查」这条映射停用了
        jdbc.update("UPDATE `catalog_symptom_rule` SET `enabled` = 0 WHERE `item_code` = 'HE-013'");
        ApiClient.ApiCall mappingOff = api.post("/api/v1/app/service-recommendations",
                java.util.Map.of("text", "拉稀两天了"), token);
        jdbc.update("UPDATE `catalog_symptom_rule` SET `enabled` = 1 WHERE `item_code` = 'HE-013'");

        // ② 运营把「专项检查」这个项目停用了（映射还指着它——典型的时间差）
        jdbc.update("UPDATE `service_item` SET `status` = 0 WHERE `code` = 'HE-013'");
        ApiClient.ApiCall itemOff = api.post("/api/v1/app/service-recommendations",
                java.util.Map.of("text", "拉稀两天了"), token);
        jdbc.update("UPDATE `service_item` SET `status` = 1 WHERE `code` = 'HE-013'");

        for (ApiClient.ApiCall call : List.of(mappingOff, itemOff)) {
            assertThat(call.code()).as(call.body().toPrettyString()).isZero();
            assertThat(call.data().path("recommendations")).hasSize(1);
            assertThat(call.data().path("recommendations").get(0).path("item_code").asText())
                    .isEqualTo("HE-012");
            assertThat(call.data().path("degraded").asBoolean()).isFalse();
        }
    }

    @Test
    @DisplayName("需要登录；描述为空或超长回 40001")
    void requiresLoginAndValidText() {
        String token = api.registerAndGetAccessToken(PHONE);

        assertThat(api.post("/api/v1/app/service-recommendations",
                java.util.Map.of("text", "拉稀两天了"), null).code()).isEqualTo(40100);
        assertThat(api.post("/api/v1/app/service-recommendations",
                java.util.Map.of("text", ""), token).code()).isEqualTo(40001);
        assertThat(api.post("/api/v1/app/service-recommendations",
                java.util.Map.of("text", "长".repeat(501)), token).code()).isEqualTo(40001);
    }

    @Test
    @DisplayName("它是「找服务」不是「咨询」：不落 AI 留痕、不占 AI 免费额度")
    void doesNotWriteConsultTrace() {
        String token = api.registerAndGetAccessToken(PHONE);
        RECEIVED.clear();

        assertThat(api.post("/api/v1/app/service-recommendations",
                java.util.Map.of("text", "拉稀两天了"), token).code()).isZero();

        // ai_consult 一行都不该多出来：免费额度就是按这张表的当日行数算的（ADR-0024），
        // 所以「表里没有」同时证明了两件事——没落留痕、没消耗额度
        Integer consults = jdbc.queryForObject("SELECT COUNT(*) FROM `ai_consult`", Integer.class);
        assertThat(consults).isZero();
        // 只调了 AI 侧的症状识别一次（不是 consult）
        assertThat(RECEIVED).hasSize(1);
    }

    // ------------------------------------------------------------ 打桩

    private static String matches() {
        return """
                {"matches":[{"symptom":"腹泻","entry_code":"K-0011","title":"犬猫腹泻的家庭观察要点",
                             "risk_hint":"yellow"}],"knowledge_check":"ok"}
                """;
    }

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/symptom-match", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                RECEIVED.add(body);
                StubReply reply = RESPONDER.get().reply(body);
                byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(reply.status(), bytes.length);
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
