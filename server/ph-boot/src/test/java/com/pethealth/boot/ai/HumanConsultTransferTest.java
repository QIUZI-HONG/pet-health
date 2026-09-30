package com.pethealth.boot.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethealth.api.app.AiConsultRequest;
import com.pethealth.boot.support.ApiClient;
import com.pethealth.boot.provider.ProviderApiTestSupport;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 转人工咨询（F006 的出口）：口径见 {@code HumanConsultService} —— **登记工单给平台运营**，
 * 不接支付（钱在门店付，ADR-0036）、不直接派给服务者（派给谁需要匹配规则，那是一张独立的票）。
 *
 * <p>六条：受理（含幂等）、归属校验（不该看到别人的单子）、用户收到受理消息、运营队列、
 * 回复后用户收到回复消息（文案就是运营写的那句）、终态不可再改。
 * AI 侧用 JDK HttpServer 打桩（与 {@code AiConsultTest} 同一手法），因为转人工要先有一次咨询。
 */
@DisplayName("转人工咨询（F006 / 工单交给运营，不接支付）")
class HumanConsultTransferTest extends ProviderApiTestSupport {

    private static final String PHONE = "13900004001";
    private static final HttpServer STUB = startStub();

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
    }

    @Test
    @DisplayName("受理并幂等：连转两次拿到同一条工单，用户只收到一条受理消息")
    void transferIsIdempotent() {
        String token = api.registerAndGetAccessToken(PHONE);
        long petId = api.createPet(token, "豆豆");
        long consultId = consult(token, petId);

        ApiClient.ApiCall first = api.post("/api/v1/app/pets/" + petId + "/ai-consults/" + consultId + "/transfer",
                Map.of(), token);
        ApiClient.ApiCall again = api.post("/api/v1/app/pets/" + petId + "/ai-consults/" + consultId + "/transfer",
                Map.of(), token);

        assertThat(first.code()).as(first.body().toPrettyString()).isZero();
        assertThat(again.code()).isZero();
        assertThat(again.data().path("id").asLong()).isEqualTo(first.data().path("id").asLong());
        assertThat(first.data().path("status").asInt()).isZero();
        // 风险等级从咨询带过来（打桩的答复是黄色 → 2）
        assertThat(first.data().path("risk_level").asInt()).isEqualTo(2);

        // 幂等也覆盖消息：连点两下不该收到两条一模一样的受理通知
        assertThat(messageCount("human-consult-accepted-" + consultId)).isEqualTo(1);
    }

    @Test
    @DisplayName("归属校验：别人的咨询（或别的宠物）一律 40400，不泄露「这个 id 存在」")
    void cannotTransferOthersConsult() {
        String owner = api.registerAndGetAccessToken(PHONE);
        long petId = api.createPet(owner, "豆豆");
        long consultId = consult(owner, petId);

        String stranger = api.registerAndGetAccessToken("13900004002");
        long strangerPet = api.createPet(stranger, "煤球");

        // 别人的咨询
        assertThat(api.post("/api/v1/app/pets/" + strangerPet + "/ai-consults/" + consultId + "/transfer",
                Map.of(), stranger).code()).isEqualTo(40400);
        // 自己的咨询但挂到别的宠物下
        assertThat(api.post("/api/v1/app/pets/" + strangerPet + "/ai-consults/" + consultId + "/transfer",
                Map.of(), owner).code()).isEqualTo(40400);
        // 不存在的咨询
        assertThat(api.post("/api/v1/app/pets/" + petId + "/ai-consults/99999999/transfer",
                Map.of(), owner).code()).isEqualTo(40400);
    }

    @Test
    @DisplayName("运营队列：待处理在前；回复后用户收到回复消息，文案就是运营写的那句")
    void operatorRepliesAndUserGetsMessage() {
        String token = api.registerAndGetAccessToken(PHONE);
        long petId = api.createPet(token, "豆豆");
        long consultId = consult(token, petId);
        long ticketId = api.post("/api/v1/app/pets/" + petId + "/ai-consults/" + consultId + "/transfer",
                Map.of(), token).data().path("id").asLong();

        ApiClient.ApiCall queue = api.get("/api/v1/admin/ai/human-consults?status=0", adminToken());
        assertThat(queue.code()).as(queue.body().toPrettyString()).isZero();
        JsonNode row = null;
        for (JsonNode node : queue.data().path("list")) {
            if (node.path("id").asLong() == ticketId) {
                row = node;
            }
        }
        assertThat(row).as("工单应当出现在待处理队列里").isNotNull();
        assertThat(row.path("consult_id").asLong()).isEqualTo(consultId);
        assertThat(row.path("operator_id").asLong()).isZero();

        // 回复必须带话：空回复等于让用户白等一次
        assertThat(api.put("/api/v1/admin/ai/human-consults/" + ticketId,
                Map.of("status", 1), adminToken()).code()).isEqualTo(40001);

        ApiClient.ApiCall replied = api.put("/api/v1/admin/ai/human-consults/" + ticketId,
                Map.of("status", 1, "reply_note", "建议尽快到院做个血常规，先别自行用药。"), adminToken());
        assertThat(replied.code()).as(replied.body().toPrettyString()).isZero();
        assertThat(replied.data().path("status").asInt()).isEqualTo(1);
        assertThat(replied.data().path("operator_id").asLong()).isPositive();
        assertThat(replied.data().path("handled_at").isNull()).isFalse();

        // 用户收到回复消息（文案原样）
        assertThat(jdbc.queryForObject(
                "SELECT `content` FROM `message` WHERE `dedup_key` = ?", String.class,
                "human-consult-replied-" + ticketId)).isEqualTo("建议尽快到院做个血常规，先别自行用药。");
        // 工单不再出现在待处理队列
        ApiClient.ApiCall pending = api.get("/api/v1/admin/ai/human-consults?status=0", adminToken());
        assertThat(pending.data().path("list").findValuesAsText("id")).doesNotContain(String.valueOf(ticketId));
    }

    @Test
    @DisplayName("终态不可再改：已回复的工单再处置回 40900（处理结果是一次性的）")
    void handledTicketCannotBeReopened() {
        String token = api.registerAndGetAccessToken(PHONE);
        long petId = api.createPet(token, "豆豆");
        long consultId = consult(token, petId);
        long ticketId = api.post("/api/v1/app/pets/" + petId + "/ai-consults/" + consultId + "/transfer",
                Map.of(), token).data().path("id").asLong();

        assertThat(api.put("/api/v1/admin/ai/human-consults/" + ticketId,
                Map.of("status", 2), adminToken()).code()).isZero();
        assertThat(api.put("/api/v1/admin/ai/human-consults/" + ticketId,
                Map.of("status", 1, "reply_note", "再来一次"), adminToken()).code()).isEqualTo(40900);
        // 关闭不要求写回复（问清了不需要回的那种）
        assertThat(api.put("/api/v1/admin/ai/human-consults/99999999",
                Map.of("status", 1, "reply_note", "x"), adminToken()).code()).isEqualTo(40400);
    }

    @Test
    @DisplayName("这两条接口都要求身份：C 端要登录、运营侧要 admin 域")
    void requiresIdentity() {
        String token = api.registerAndGetAccessToken(PHONE);
        long petId = api.createPet(token, "豆豆");
        long consultId = consult(token, petId);

        assertThat(api.post("/api/v1/app/pets/" + petId + "/ai-consults/" + consultId + "/transfer",
                Map.of(), null).code()).isEqualTo(40100);
        // C 端令牌调运营接口 = 没登录（登录域校验，ADR-0012）
        assertThat(api.get("/api/v1/admin/ai/human-consults", token).code()).isEqualTo(40100);
    }

    // ---------------------------------------------------------------- 辅助

    /** 造一次咨询（AI 侧打桩回黄色），返回 consult id。 */
    private long consult(String token, long petId) {
        ApiClient.ApiCall call = api.post("/api/v1/app/pets/" + petId + "/ai-consults",
                new AiConsultRequest("今天吐了两次，精神还行", null), token);
        assertThat(call.code()).as(call.body().toPrettyString()).isZero();
        return call.data().path("id").asLong();
    }

    private int messageCount(String dedupKey) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM `message` WHERE `dedup_key` = ?",
                Integer.class, dedupKey);
        return count == null ? 0 : count;
    }

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/consult", exchange -> {
                byte[] bytes = """
                        {"risk_level":2,"possible_causes":["饮食不当"],"action_suggestion":"建议尽快就医",
                         "need_hospital":true,"care_tips":[],"citations":[],"images_used":0,
                         "red_flag_hits":[],"guard_hits":[],"red_flag_check":"ok","degraded":false,
                         "degrade_reason":null,"model_name":"stub","model_version":"stub",
                         "prompt_version":"p0-code","latency_ms":1,"prompt_tokens":1,"completion_tokens":1}
                        """.getBytes(StandardCharsets.UTF_8);
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
