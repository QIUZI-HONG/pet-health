package com.pethealth.boot.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethealth.ai.metrics.AiMetrics;
import com.pethealth.api.app.AiConsultRequest;
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
 * AI 咨询最小闭环（切片 #98 的验收标准）。
 *
 * <p>AI 服务用 **JDK 自带的 HttpServer 打桩**，不是 mock 掉客户端：要验的东西里有一半在传输层
 * ——超时算不算降级、请求体里的媒体地址长什么样、非 2xx 怎么处理。把客户端 mock 掉，这些就全测不到了，
 * 而那正是「端到端跑通」与「单元测试绿」之间最容易出偏差的地方。
 *
 * <p>真打模型不在这里（需要 key 与网络，见 {@code ai/tests} 的 live 标记）。
 */
@DisplayName("AI 咨询闭环（切片 #98 / ADR-0021）")
class AiConsultTest extends IntegrationTestBase {

    private static final String PHONE = "13900002001";

    /** 打桩的 AI 服务：每个测试通过 {@link #responder} 决定它回什么。 */
    private static final HttpServer STUB = startStub();

    /** 请求体 → 响应体（JSON 字符串）。测试里直接换掉它。 */
    private static final AtomicReference<Responder> RESPONDER =
            new AtomicReference<Responder>(body -> new StubReply(200, aiResponse(1, false)));

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

    @Autowired
    private io.micrometer.core.instrument.MeterRegistry registry;

    private ApiClient api;

    @BeforeEach
    void setUpClient() {
        api = new ApiClient(rest, objectMapper);
        RESPONDER.set(body -> new StubReply(200, aiResponse(1, false)));
        RECEIVED.clear();
    }

    @AfterEach
    void resetStub() {
        RESPONDER.set(body -> new StubReply(200, aiResponse(1, false)));
    }

    // ------------------------------------------------------------ 主链路

    @Test
    @DisplayName("文字提问：返回分级 / 原因 / 行动建议，并把留痕落库")
    void consultReturnsGradingAndPersistsTrace() {
        String token = api.registerAndGetAccessToken(PHONE);
        long petId = api.createPet(token, "豆豆");

        RESPONDER.set(body -> new StubReply(200, """
                {"risk_level":2,"possible_causes":["饮食不当","肠胃炎"],"action_suggestion":"建议尽快就医",
                 "need_hospital":true,"care_tips":["禁食 4 小时"],"citations":[],"images_used":0,
                 "red_flag_hits":[],"guard_hits":[],"red_flag_check":"ok","degraded":false,
                 "degrade_reason":null,"model_name":"deepseek-flash","model_version":"deepseek-flash",
                 "prompt_version":"p0-code","latency_ms":4177,
                 "prompt_tokens":1830,"completion_tokens":642}
                """));
        RECEIVED.clear();

        ApiClient.ApiCall call = api.post("/api/v1/app/pets/" + petId + "/ai-consults",
                new AiConsultRequest("今天吐了两次，精神不太好", null), token);

        assertThat(call.code()).isZero();
        JsonNode data = call.data();
        assertThat(data.path("risk_level").asInt()).isEqualTo(2);
        // 打点：分级分布与降级率是「提示词改坏 / 模型静默换版」的最早信号（ADR-0029）。
        // 界面上看不出任何异常，所以这条断言是这两个计数器唯一的守卫
        assertThat(registry.find(AiMetrics.CONSULT).tag("outcome", AiMetrics.OUTCOME_OK).counter().count())
                .isGreaterThanOrEqualTo(1);
        assertThat(registry.find(AiMetrics.RISK_LEVEL).tag("level", "2").counter().count())
                .isGreaterThanOrEqualTo(1);
        assertThat(data.path("possible_causes")).hasSize(2);
        assertThat(data.path("need_hospital").asBoolean()).isTrue();
        assertThat(data.path("degraded").asBoolean()).isFalse();
        assertThat(data.path("prompt_version").asText()).isEqualTo("p0-code");
        assertThat(data.path("latency_ms").asInt()).isEqualTo(4177);
        assertThat(data.path("disclaimer").asText()).contains("不能替代兽医诊断");

        // 留痕：模型与提示词版本、延迟、问题密文，以及**本轮 token 用量**
        // （日预算告警按它估算花费，ADR-0026）
        var row = jdbc.queryForMap("SELECT * FROM ai_consult WHERE pet_id = ?", petId);
        assertThat(((Number) row.get("prompt_tokens")).intValue()).isEqualTo(1830);
        assertThat(((Number) row.get("completion_tokens")).intValue()).isEqualTo(642);
        assertThat(row.get("model_version")).isEqualTo("deepseek-flash");
        assertThat(row.get("prompt_version")).isEqualTo("p0-code");
        assertThat(((Number) row.get("latency_ms")).intValue()).isEqualTo(4177);
        assertThat(((Number) row.get("risk_level")).intValue()).isEqualTo(2);
        assertThat((String) row.get("question_enc"))
                .as("问题原文按病历口径加密（ADR-0013），不能是明文")
                .doesNotContain("吐了两次")
                .isNotBlank();
    }

    @Test
    @DisplayName("缺文字描述直接拒绝：纯图片分诊不可用")
    void questionIsRequired() {
        String token = api.registerAndGetAccessToken(PHONE);
        long petId = api.createPet(token, "豆豆");

        ApiClient.ApiCall blank = api.post("/api/v1/app/pets/" + petId + "/ai-consults",
                new AiConsultRequest("", null), token);
        ApiClient.ApiCall tooShort = api.post("/api/v1/app/pets/" + petId + "/ai-consults",
                new AiConsultRequest("吐", null), token);

        assertThat(blank.code()).isEqualTo(40001);
        assertThat(tooShort.code()).isEqualTo(40001);
        assertThat(RECEIVED).as("被拒的请求不该已经花掉一次模型调用").isEmpty();
    }

    @Test
    @DisplayName("带图片：把该图片的签名读地址发给 AI 服务，并留痕张数")
    void imageUrlsArePassedToAiService() {
        String token = api.registerAndGetAccessToken(PHONE);
        long petId = api.createPet(token, "豆豆");

        String uploadUrl = api.post("/api/v1/app/files/presign",
                        new com.pethealth.api.app.FilePresignRequest("ai_consult", petId,
                                List.of(new com.pethealth.api.app.FilePresignRequest.Item("image/png", null, null))),
                        token)
                .data().get(0).path("upload_url").asText();
        assertThat(api.putBinary(uploadUrl, tinyPng()).status()).isEqualTo(204);
        long fileId = api.get("/api/v1/app/files?biz_type=ai_consult", token).data().get(0).path("id").asLong();

        RESPONDER.set(body -> new StubReply(200, """
                {"risk_level":2,"possible_causes":[],"action_suggestion":"建议就医","need_hospital":true,
                 "care_tips":[],"citations":[],"images_used":1,"red_flag_hits":[],"guard_hits":[],
                 "red_flag_check":"ok","degraded":false,"degrade_reason":null,"model_name":"deepseek-flash",
                 "model_version":"deepseek-flash","prompt_version":"p0-code","latency_ms":1000}
                """));
        RECEIVED.clear();

        ApiClient.ApiCall call = api.post("/api/v1/app/pets/" + petId + "/ai-consults",
                new AiConsultRequest("皮肤上有个红点，拍了两天照片", List.of(fileId)), token);

        assertThat(call.code()).isZero();
        assertThat(call.data().path("degraded").asBoolean()).isFalse();
        assertThat(RECEIVED).hasSize(1);
        assertThat(RECEIVED.get(0))
                .as("发给 AI 服务的应当是**绝对**签名读地址——AI 服务要自己去把图取回来、"
                        + "内联给模型（相对路径它取不到，测试报告 D7）")
                .contains("http://127.0.0.1:")
                .contains("/api/v1/open/files/" + fileId)
                .contains("token=");
        assertThat(((Number) jdbc.queryForMap("SELECT image_count FROM ai_consult WHERE pet_id = ?", petId)
                .get("image_count")).intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("红线短路：命中即判红，并把规则编号与「未经模型」一起返回")
    void redFlagShortCircuitIsVisible() {
        String token = api.registerAndGetAccessToken(PHONE);
        long petId = api.createPet(token, "豆豆");

        RESPONDER.set(body -> new StubReply(200, """
                {"risk_level":3,"possible_causes":[],"action_suggestion":"持续呕吐会脱水，立即送医",
                 "need_hospital":true,"care_tips":[],"citations":[],"images_used":0,
                 "red_flag_hits":["RF-007"],"guard_hits":["RF-007:呕吐不止"],
                 "red_flag_check":"ok","degraded":false,"degrade_reason":null,
                 "model_name":"rule:red_flag","model_version":"","prompt_version":"p0-code","latency_ms":0}
                """));
        RECEIVED.clear();

        ApiClient.ApiCall call = api.post("/api/v1/app/pets/" + petId + "/ai-consults",
                new AiConsultRequest("从昨晚开始呕吐不止，喝水都吐", null), token);

        assertThat(call.data().path("risk_level").asInt()).isEqualTo(3);
        assertThat(call.data().path("red_flag_hits").get(0).asText()).isEqualTo("RF-007");
        assertThat(jdbc.queryForMap("SELECT red_flag_hits FROM ai_consult WHERE pet_id = ?", petId)
                .get("red_flag_hits").toString()).contains("RF-007");
        // 这条与 model_name=rule:red_flag 是同一件事的用户可见面：结论由规则给出，模型没参与。
        // 用户与事后复核都该分得清「模型判的红」和「规则判的红」
        assertThat(call.data().path("disclaimer").asText())
                .contains("红线规则").contains("未经模型");
        assertThat(registry.find(AiMetrics.CONSULT).tag("outcome", AiMetrics.OUTCOME_RED_FLAG).counter().count())
                .as("红线短路要与模型判断在指标上分得开——否则分级准确率的样本会被规则判定污染（ADR-0021）")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("免责声明不承诺「知识来源」——检索层还没接，就不能这样对用户说")
    void disclaimerDoesNotOverpromiseKnowledgeSource() {
        String token = api.registerAndGetAccessToken(PHONE);
        long petId = api.createPet(token, "豆豆");

        ApiClient.ApiCall call = api.post("/api/v1/app/pets/" + petId + "/ai-consults",
                new AiConsultRequest("今天吐了两次", null), token);

        // 检索层未实现（#100/#101），citations 恒为空，交进模型的上下文里没有任何知识条目。
        // 因此声明里**不能**出现「知识库」「知识来源」这类承诺——那句话现在给不出对应物。
        // 接上检索后要想改回这类措辞，先让这条用例红，再同时给出真正的来源。
        assertThat(call.data().path("citations")).isEmpty();
        assertThat(call.data().path("disclaimer").asText())
                .as("没接检索就不能声称有知识来源")
                .doesNotContain("知识库").doesNotContain("知识来源")
                .contains("不能替代兽医诊断");
    }

    // ------------------------------------------------------------ 降级

    @Test
    @DisplayName("AI 服务不可用：降级返回保守答复，HTTP 仍是 200 且留痕 degraded")
    void aiServiceDownDegradesGracefully() {
        String token = api.registerAndGetAccessToken(PHONE);
        long petId = api.createPet(token, "豆豆");

        RESPONDER.set(body -> new StubReply(500, "{\"detail\":\"boom\"}"));

        ApiClient.ApiCall call = api.post("/api/v1/app/pets/" + petId + "/ai-consults",
                new AiConsultRequest("今天精神不太好", null), token);

        assertThat(call.status()).as("降级不是报错：HTTP 200（交付文档 2.4）").isEqualTo(200);
        assertThat(call.code()).isZero();
        assertThat(call.data().path("degraded").asBoolean()).isTrue();
        assertThat(call.data().path("need_hospital").asBoolean()).isTrue();
        assertThat(call.data().path("action_suggestion").asText()).contains("兽医");
        // 降级的声明不能说「依据…与 AI 判断」——这一轮模型没给出可用结果（评审指出）：
        // 三分支各说各的事实：降级 / 红线规则 / 模型判断
        assertThat(call.data().path("disclaimer").asText())
                .as("降级时不能声称有 AI 判断")
                .contains("未能走通 AI 判断").doesNotContain("与 AI 判断，");

        var row = jdbc.queryForMap("SELECT degraded, degrade_reason FROM ai_consult WHERE pet_id = ?", petId);
        assertThat(((Number) row.get("degraded")).intValue()).isEqualTo(1);
        // 留痕表里存的是**内部明细**（降级码 + 原因），给用户看的中文按码映射（测试报告 D6）
        assertThat(row.get("degrade_reason").toString()).contains("ai_service_unreachable");
        assertThat(call.data().path("degrade_reason").asText())
                .as("用户看到的是人话，不是内部异常名：" + call.data())
                .isEqualTo("AI 服务暂时不可用，已按更保守的建议给你");
    }

    @Test
    @DisplayName("降级原因里的内部细节不进用户可见文案（模型原文、异常类名都挡在留痕里）")
    void degradedReasonIsNotLeakedToUser() {
        String token = api.registerAndGetAccessToken(PHONE);
        long petId = api.createPet(token, "豆豆");

        // 桩服务回一条「降级 + 明细里带模型原文与内部异常名」的响应（真实降级路径就是这样）
        RESPONDER.set(body -> new StubReply(200, """
                {"risk_level":3,"possible_causes":[],"action_suggestion":"建议立即就医","need_hospital":true,
                 "care_tips":[],"citations":[],"images_used":0,"red_flag_hits":[],"guard_hits":[],
                 "red_flag_check":"ok","degraded":true,"degrade_code":"model_output_invalid",
                 "degrade_reason":"ModelOutputInvalid: 工具参数不是合法 JSON：risk_level=true 疑似胰腺炎",
                 "model_name":"deepseek-flash","model_version":"v1","prompt_version":"p0-code","latency_ms":0}
                """));

        ApiClient.ApiCall call = api.post("/api/v1/app/pets/" + petId + "/ai-consults",
                new AiConsultRequest("今天吐了两次", null), token);

        String reason = call.data().path("degrade_reason").asText();
        assertThat(reason).isEqualTo("模型这次没有按格式回答，已按更保守的结论给你");
        assertThat(reason).doesNotContain("ModelOutputInvalid", "JSON", "胰腺炎");

        // 留痕里保留明细，供事后归因
        assertThat(jdbc.queryForMap("SELECT degrade_reason FROM ai_consult WHERE pet_id = ?", petId)
                .get("degrade_reason").toString()).contains("model_output_invalid");
    }

    @Test
    @DisplayName("免费额度只提示不拦截：到量后照常给结论（ADR-0024）")
    void quotaIsCountedButNotEnforced() {
        String token = api.registerAndGetAccessToken(PHONE);
        long petId = api.createPet(token, "豆豆");

        java.util.List<Integer> remaining = new ArrayList<>();
        java.util.List<Integer> codes = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            ApiClient.ApiCall call = api.post("/api/v1/app/pets/" + petId + "/ai-consults",
                    new AiConsultRequest("第 " + i + " 次提问：今天精神一般", null), token);
            codes.add(call.code());
            remaining.add(call.data().path("remaining_today").asInt());
        }

        assertThat(remaining).containsExactly(2, 1, 0, 0);
        assertThat(codes).as("到量后不返回错误——解锁路径在 #112，现在拦截会把用户挡死")
                .containsOnly(0);
        assertThat(api.post("/api/v1/app/pets/" + petId + "/ai-consults",
                new AiConsultRequest("第四次之后仍然可以问", null), token)
                .data().path("quota_per_day").asInt()).isEqualTo(3);
    }

    // ------------------------------------------------------------ 知识来源与引用口径（#100 / #101）

    @Test
    @DisplayName("有已复核（vetted）引用：来源下发给 C 端，且这时才允许说「平台知识库」")
    void vettedCitationsUnlockTheKnowledgeBaseWording() {
        String token = api.registerAndGetAccessToken(PHONE);
        long petId = api.createPet(token, "豆豆");

        RESPONDER.set(body -> new StubReply(200, """
                {"risk_level":2,"possible_causes":["可能：饮食不当 [K-0001]"],
                 "action_suggestion":"观察 24 小时","need_hospital":true,"care_tips":[],
                 "citations":[{"entry_id":"K-0001","title":"犬核心疫苗的接种时间表","category":"vaccine",
                   "source_title":"WSAVA 2024 犬猫疫苗接种指南","source_version":"2024 版",
                   "source_url":"https://wsava.org/","review_status":"vetted"}],
                 "unvetted_hits":[],"images_used":0,"red_flag_hits":[],"grading_rule_hits":[],
                 "guard_hits":[],"red_flag_check":"ok","retrieval_check":"ok","degraded":false,
                 "degrade_reason":null,"model_name":"stub","model_version":"stub",
                 "prompt_version":"p1","latency_ms":10}
                """));
        RECEIVED.clear();

        ApiClient.ApiCall call = api.post("/api/v1/app/pets/" + petId + "/ai-consults",
                new AiConsultRequest("疫苗要打几针", null), token);

        assertThat(call.code()).isZero();
        var citation = call.data().path("citations").get(0);
        assertThat(citation.path("entry_id").asText()).isEqualTo("K-0001");
        // 来源要能点回原始资料：只给内部编号等于来源不可追溯（63 号调研 §4.5）
        assertThat(citation.path("source_title").asText()).contains("WSAVA");
        assertThat(citation.path("review_status").asText()).isEqualTo("vetted");
        // **「基于知识库」的解禁条件是「至少一条 vetted 引用」**（ADR-0040 第二节）
        assertThat(call.data().path("disclaimer").asText()).contains("平台知识库");
        assertThat(call.data().path("unvetted_used").asBoolean()).isFalse();

        var row = jdbc.queryForMap("SELECT citations, unvetted_hits, retrieval_check FROM ai_consult "
                + "WHERE pet_id = ?", petId);
        assertThat(row.get("citations").toString()).contains("K-0001").contains("WSAVA");
        assertThat(row.get("unvetted_hits")).isNull();
        assertThat(row.get("retrieval_check")).isEqualTo("ok");
    }

    @Test
    @DisplayName("只命中未复核条目：不进 citations，且回答里必须明说「尚未经兽医复核」")
    void unvettedHitsAreDisclosedButNotCited() {
        String token = api.registerAndGetAccessToken(PHONE);
        long petId = api.createPet(token, "豆豆");

        RESPONDER.set(body -> new StubReply(200, """
                {"risk_level":2,"possible_causes":[],"action_suggestion":"观察 24 小时",
                 "need_hospital":true,"care_tips":[],"citations":[],"unvetted_hits":["K-0010"],
                 "images_used":0,"red_flag_hits":[],"grading_rule_hits":[],"guard_hits":[],
                 "red_flag_check":"ok","retrieval_check":"ok","degraded":false,"degrade_reason":null,
                 "model_name":"stub","model_version":"stub","prompt_version":"p1","latency_ms":10}
                """));
        RECEIVED.clear();

        ApiClient.ApiCall call = api.post("/api/v1/app/pets/" + petId + "/ai-consults",
                new AiConsultRequest("今天吐了两次", null), token);

        // 未复核内容不许被当作依据：它不出现在来源列表里，只以一句如实说明出现
        assertThat(call.data().path("citations")).isEmpty();
        assertThat(call.data().path("unvetted_used").asBoolean()).isTrue();
        String disclaimer = call.data().path("disclaimer").asText();
        assertThat(disclaimer).contains("尚未经兽医复核");
        assertThat(disclaimer).as("没有 vetted 引用时 C 端不许出现「知识库」")
                .doesNotContain("知识库");

        // 留痕把两件事分开记：事后才能回答「这句话当时有依据吗」
        var row = jdbc.queryForMap("SELECT citations, unvetted_hits FROM ai_consult WHERE pet_id = ?", petId);
        assertThat(row.get("citations")).isNull();
        assertThat(row.get("unvetted_hits").toString()).contains("K-0010");
    }

    @Test
    @DisplayName("检索读不到知识域：回答照常（HTTP 200、不降级），如实记 retrieval_check=unavailable")
    void retrievalUnavailableIsRecordedNotFatal() {
        String token = api.registerAndGetAccessToken(PHONE);
        long petId = api.createPet(token, "豆豆");

        RESPONDER.set(body -> new StubReply(200, """
                {"risk_level":2,"possible_causes":["可能：饮食不当"],"action_suggestion":"观察 24 小时",
                 "need_hospital":true,"care_tips":[],"citations":[],"unvetted_hits":[],
                 "images_used":0,"red_flag_hits":[],"grading_rule_hits":[],"guard_hits":[],
                 "red_flag_check":"ok","retrieval_check":"unavailable","degraded":false,
                 "degrade_reason":null,"model_name":"stub","model_version":"stub",
                 "prompt_version":"p1","latency_ms":10}
                """));
        RECEIVED.clear();

        ApiClient.ApiCall call = api.post("/api/v1/app/pets/" + petId + "/ai-consults",
                new AiConsultRequest("今天吐了两次", null), token);

        // 「检索失败 = 降级为无来源的通用建议，咨询本身仍然成功」（ADR-0040 第二节）
        assertThat(call.status()).isEqualTo(200);
        assertThat(call.data().path("degraded").asBoolean()).isFalse();
        assertThat(call.data().path("citations")).isEmpty();
        assertThat(call.data().path("disclaimer").asText()).doesNotContain("知识库");
        assertThat(jdbc.queryForMap("SELECT retrieval_check FROM ai_consult WHERE pet_id = ?", petId)
                .get("retrieval_check")).isEqualTo("unavailable");
    }

    @Test
    @DisplayName("分级规则命中进留痕：归因要能分清「模型判的」与「规则抬的档」")
    void gradingRuleHitsArePersisted() {
        String token = api.registerAndGetAccessToken(PHONE);
        long petId = api.createPet(token, "豆豆");

        RESPONDER.set(body -> new StubReply(200, """
                {"risk_level":3,"possible_causes":[],"action_suggestion":"立即送医","need_hospital":true,
                 "care_tips":["老年动物耐受差，建议立即就医。"],"citations":[],"unvetted_hits":[],
                 "images_used":0,"red_flag_hits":[],"grading_rule_hits":["GR-002"],"guard_hits":[],
                 "red_flag_check":"ok","retrieval_check":"empty","degraded":false,"degrade_reason":null,
                 "model_name":"stub","model_version":"stub","prompt_version":"p1","latency_ms":10}
                """));
        RECEIVED.clear();

        ApiClient.ApiCall call = api.post("/api/v1/app/pets/" + petId + "/ai-consults",
                new AiConsultRequest("最近没精神", null), token);

        assertThat(call.code()).isZero();
        assertThat(jdbc.queryForMap("SELECT grading_rule_hits, retrieval_check FROM ai_consult "
                + "WHERE pet_id = ?", petId).get("grading_rule_hits").toString()).contains("GR-002");
    }

    // ------------------------------------------------------------ 越权

    @Test
    @DisplayName("咨询只能针对自己的宠物；未登录不给调")
    void ownershipAndLoginEnforced() {
        String tokenA = api.registerAndGetAccessToken(PHONE);
        long petA = api.createPet(tokenA, "豆豆");
        String tokenB = api.registerAndGetAccessToken("13900002002");

        ApiClient.ApiCall foreign = api.post("/api/v1/app/pets/" + petA + "/ai-consults",
                new AiConsultRequest("我看看别人家狗的情况", null), tokenB);
        ApiClient.ApiCall anonymous = api.post("/api/v1/app/pets/" + petA + "/ai-consults",
                new AiConsultRequest("没登录也想问", null), null);

        assertThat(foreign.code()).isEqualTo(40400);
        assertThat(anonymous.code()).isEqualTo(40100);
        assertThat(RECEIVED).as("越权请求不该花掉模型调用").isEmpty();
    }

    // ------------------------------------------------------------ 打桩

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/consult", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                RECEIVED.add(body);
                // 记下请求体后立刻清掉：`RECEIVED` 只服务于「这次请求发了什么」这一个断言场景
                StubReply reply = RESPONDER.get().reply(body);
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

    private static String aiResponse(int riskLevel, boolean degraded) {
        return """
                {"risk_level":%d,"possible_causes":[],"action_suggestion":"观察","need_hospital":false,
                 "care_tips":[],"citations":[],"images_used":0,"red_flag_hits":[],"guard_hits":[],
                 "red_flag_check":"ok","degraded":%s,"degrade_reason":null,"model_name":"stub",
                 "model_version":"stub","prompt_version":"p0-code","latency_ms":1}
                """.formatted(riskLevel, degraded);
    }

    private static byte[] tinyPng() {
        // 1x1 的真实 PNG：类型按魔数判定，随便几个字节是过不去的（ADR-0020）
        return java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
    }
}
