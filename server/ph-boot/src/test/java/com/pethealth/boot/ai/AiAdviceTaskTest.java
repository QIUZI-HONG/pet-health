package com.pethealth.boot.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethealth.api.app.AiConsultRequest;
import com.pethealth.boot.support.ApiClient;
import com.pethealth.boot.support.IntegrationTestBase;
import com.pethealth.common.time.AppTime;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 一次 AI 咨询 → 记一次 {@code AI_ADVICE} 行为 → 任务中心「查看 AI 建议」的进度动起来。
 *
 * <p>为什么要单独盯这条：这条任务此前**没有任何生产者**（`point_record` 里永远没有
 * {@code AI_ADVICE} 这一行），于是 C 端那条任务永远是 0/1——它看起来「就是还没做」，
 * 而不是「坏了」，所以没有任何症状能让人发现它。这个用例就是它的症状。
 *
 * <p>断言全在**外部可观察状态**上（流水与任务进度），不看有没有调用过谁：
 *
 * <ul>
 *   <li>咨询成功 → 流水恰好 1 条 {@code AI_ADVICE}（**0 分**：记行为不发分）、任务 1/1 已完成；
 *   <li>降级 → **不记**（这一轮没产出建议，见 AiConsultService 里那段判断）；
 *   <li>红线短路 → **记**（结论由急症规则给，用户确实拿到了建议）；
 *   <li>行为被运营停用 → 咨询照常成功（记行为失败不该让用户拿不到回答）。
 * </ul>
 *
 * <p>AI 服务用 JDK 自带的 HttpServer 打桩（与 {@code AiConsultTest} 同一套做法）：走真的 HTTP，
 * 所以「降级」这条路径测的是真实的传输层失败，而不是 mock 出来的一个标记位。
 */
@DisplayName("AI 建议 → 任务中心进度（DAILY_AI_ADVICE 的生产者）")
class AiAdviceTaskTest extends IntegrationTestBase {

    private static final String PHONE = "13900003001";

    /** 打桩的 AI 服务：每个用例通过 {@link #REPLY} 决定它回什么。 */
    private static final HttpServer STUB = startStub();

    private static final AtomicReference<StubReply> REPLY =
            new AtomicReference<>(new StubReply(200, adviceResponse()));

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
        REPLY.set(new StubReply(200, adviceResponse()));
        // 积分那两张表不在基类的清理名单里（跨切片共享的基类），本切片自己清。
        // **种子不动**（point_behavior / point_task 的七行与六行），只把本用例依赖的那两行复位：
        // AI_ADVICE 是 0 分 + 每日 1 次 + 启用，任务 DAILY_AI_ADVICE 是启用（V27 的种子值）
        jdbc.execute("DELETE FROM `point_record`");
        jdbc.execute("DELETE FROM `user_point`");
        jdbc.execute("UPDATE `point_behavior` SET `points` = 0, `status` = 1, `daily_count_limit` = 1 "
                + "WHERE `code` = 'AI_ADVICE'");
        jdbc.execute("UPDATE `point_task` SET `status` = 1 WHERE `code` = 'DAILY_AI_ADVICE'");
    }

    @AfterEach
    void restoreBehavior() {
        // 最后一个用例（停用行为那条）可能留下停用状态：还原成种子状态，
        // 共享容器里下一个测试类看到的应该是迁移跑完的样子
        jdbc.execute("UPDATE `point_behavior` SET `status` = 1 WHERE `code` = 'AI_ADVICE'");
    }

    @Test
    @DisplayName("咨询成功 → 1 条 AI_ADVICE 流水（0 分）、任务 1/1 已完成")
    void consultRecordsAiAdviceAndCompletesTask() {
        String token = api.registerAndGetAccessToken(PHONE);
        long userId = userIdOf(token);
        long petId = api.createPet(token, "豆豆");

        ApiClient.ApiCall call = consult(token, petId);
        assertThat(call.code()).as("咨询应当成功：" + call.body()).isZero();
        assertThat(call.data().path("degraded").asBoolean()).isFalse();

        // 先钉「恰好一条」再读字段：queryForMap 在 0 行 / 多行时会抛一个看不出原因的异常
        var records = jdbc.queryForList("SELECT `behavior_code`, `change_amount`, `source_ref`, "
                + "`counts_toward_daily_cap` FROM `point_record` WHERE `user_id` = ?", userId);
        assertThat(records).as("一次咨询恰好记一条行为").hasSize(1);
        Map<String, Object> record = records.get(0);
        assertThat(record.get("behavior_code")).isEqualTo("AI_ADVICE");
        assertThat(((Number) record.get("change_amount")).intValue())
                .as("0 分：这条行为只推进任务，不发分（V27 的种子口径）").isZero();
        assertThat(((Number) record.get("counts_toward_daily_cap")).intValue())
                .as("0 分的行为不占每日上限——占的话一次「看 AI 建议」会白吃掉 20 分额度里的一份")
                .isZero();
        assertThat(record.get("source_ref")).as("引用带业务日：同一用户当天只记一次")
                .isEqualTo("ai-advice:" + AppTime.today());

        JsonNode task = taskOf(token, "DAILY_AI_ADVICE");
        assertThat(task).as("任务清单里必须有「查看 AI 建议」").isNotNull();
        assertThat(task.path("current_count").asInt()).as("进度动了（此前恒为 0）").isEqualTo(1);
        assertThat(task.path("target_count").asInt()).isEqualTo(1);
        assertThat(task.path("completed").asBoolean()).isTrue();
        // 任务进度 ≠ 发分：余额仍然是 0
        assertThat(jdbc.queryForObject("SELECT `balance` FROM `user_point` WHERE `user_id` = ?",
                Integer.class, userId)).isZero();
    }

    @Test
    @DisplayName("降级（AI 服务不可用）：不记行为——这一轮没产出建议，不算任务完成")
    void degradedConsultDoesNotRecord() {
        String token = api.registerAndGetAccessToken(PHONE);
        long userId = userIdOf(token);
        long petId = api.createPet(token, "豆豆");

        REPLY.set(new StubReply(500, "{\"detail\":\"boom\"}"));

        ApiClient.ApiCall call = consult(token, petId);
        assertThat(call.status()).as("降级仍是 200，不是错误页（交付文档 2.4）").isEqualTo(200);
        assertThat(call.data().path("degraded").asBoolean()).isTrue();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `point_record` WHERE `user_id` = ? "
                + "AND `behavior_code` = 'AI_ADVICE'", Integer.class, userId))
                .as("降级答复是保守话术、免责声明也明说「未能走通 AI 判断」——记它等于把没产出的咨询算成完成")
                .isZero();
        JsonNode task = taskOf(token, "DAILY_AI_ADVICE");
        assertThat(task.path("current_count").asInt()).isZero();
        assertThat(task.path("completed").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("红线短路：照记——用户拿到的是平台的急症建议，不是「什么都没有」")
    void redFlagShortCircuitStillCounts() {
        String token = api.registerAndGetAccessToken(PHONE);
        long userId = userIdOf(token);
        long petId = api.createPet(token, "豆豆");

        REPLY.set(new StubReply(200, """
                {"risk_level":3,"possible_causes":[],"action_suggestion":"建议立即就医","need_hospital":true,
                 "care_tips":[],"citations":[],"images_used":0,"red_flag_hits":["RF-001"],"guard_hits":[],
                 "red_flag_check":"ok","degraded":false,"degrade_reason":null,"model_name":"rule:red_flag",
                 "model_version":"rule:red_flag","prompt_version":"p0-code","latency_ms":3}
                """));

        ApiClient.ApiCall call = consult(token, petId);
        assertThat(call.code()).isZero();
        assertThat(call.data().path("red_flag_hits")).hasSize(1);
        assertThat(call.data().path("degraded").asBoolean()).isFalse();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `point_record` WHERE `user_id` = ? "
                + "AND `behavior_code` = 'AI_ADVICE'", Integer.class, userId))
                .as("不记的话，「问急症的人」任务永远不前进——而任务名问的就是「有没有拿到建议」")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("记行为没记成（行为被运营停用）也不拖垮咨询：接口成功、回答照给")
    void recordingFailureDoesNotBreakConsult() {
        String token = api.registerAndGetAccessToken(PHONE);
        long userId = userIdOf(token);
        long petId = api.createPet(token, "豆豆");
        jdbc.execute("UPDATE `point_behavior` SET `status` = 0 WHERE `code` = 'AI_ADVICE'");

        ApiClient.ApiCall call = consult(token, petId);

        assertThat(call.code()).as("记行为失败不该让用户拿不到回答").isZero();
        assertThat(call.data().path("action_suggestion").asText()).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `point_record` WHERE `user_id` = ?",
                Integer.class, userId)).isZero();
    }

    // ------------------------------------------------------------ 工具

    private ApiClient.ApiCall consult(String token, long petId) {
        return api.post("/api/v1/app/pets/" + petId + "/ai-consults",
                new AiConsultRequest("今天吐了两次，精神不太好", null), token);
    }

    /** 登录令牌对应的用户 id（从 /users/me 读，不猜）。 */
    private long userIdOf(String token) {
        return api.get("/api/v1/app/users/me", token).data().path("id").asLong();
    }

    /** 从 C 端积分中心的任务清单里取一条；没有这条任务时返回 null（让断言给出「任务不见了」而不是 NPE）。 */
    private JsonNode taskOf(String token, String code) {
        ApiClient.ApiCall center = api.get("/api/v1/app/points", token);
        assertThat(center.code()).as("积分中心应当能打开：" + center.body()).isZero();
        for (JsonNode task : center.data().path("tasks")) {
            if (code.equals(task.path("code").asText())) {
                return task;
            }
        }
        return null;
    }

    /** 一次「模型给出了建议」的正常答复（字段与 Python 侧的响应形状一致）。 */
    private static String adviceResponse() {
        return """
                {"risk_level":2,"possible_causes":["饮食不当"],"action_suggestion":"建议尽快就医","need_hospital":true,
                 "care_tips":["禁食 4 小时"],"citations":[],"images_used":0,"red_flag_hits":[],"guard_hits":[],
                 "red_flag_check":"ok","degraded":false,"degrade_reason":null,"model_name":"stub",
                 "model_version":"stub","prompt_version":"p0-code","latency_ms":5}
                """;
    }

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/consult", exchange -> {
                exchange.getRequestBody().readAllBytes();
                StubReply reply = REPLY.get();
                byte[] payload = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(reply.status(), payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("打桩的 AI 服务起不来", e);
        }
    }
}
