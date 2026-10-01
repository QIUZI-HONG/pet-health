package com.pethealth.boot.ai;

import com.pethealth.api.admin.AiOpsDtos;
import com.pethealth.boot.provider.ProviderApiTestSupport;
import com.pethealth.boot.support.ApiClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 运营接口（切片 #103）：提示词版本与灰度、红线词增删、分级规则、护栏词表、降级开关、抽检。
 *
 * <p>三条这个类专门守住的边界：
 *
 * <ol>
 *   <li><b>登录域</b>：C 端令牌调不了这些接口（ADR-0012）。运营与超管在 token 里还没区分，
 *       所以这里不断言「谁能改哪个字段」——那是权限矩阵（#60）的事。
 *   <li><b>写操作留痕</b>：每条改动都要留下 operator_id 与 trace_id（ADR-0011），
 *       断言落在 {@code created_by}/{@code trace_id} 上。
 *   <li><b>复核状态只能由人工复核那一个入口改</b>：通用运营接口（提示词 / 红线词 / 分级规则 /
 *       护栏词 / 开关）都不能碰 `review_status`，唯一的写入口是
 *       `POST /ai/knowledge-entries/{code}/review`，且必须填**复核人与资质**
 *       （ADR-0040 第二节禁的是「代码自动置位」，落地方式见 ADR-0054）。
 * </ol>
 *
 * <p>用 {@link ProviderApiTestSupport} 是为了拿它的 {@code adminToken()}：运营后台的登录入口
 * 还没实现，测试直接签 admin 域令牌（鉴权链路与线上一致，只有登录入口是绕过的）。
 */
@DisplayName("AI 运营接口（#103）")
class AiOpsAdminTest extends ProviderApiTestSupport {

    @AfterEach
    void cleanOpsRows() {
        // 只删本类造的行：提示词模板、红线词、分级规则、护栏词词条
        jdbc.execute("DELETE FROM knowledge_prompt_template WHERE version LIKE 'p-test%'");
        jdbc.execute("DELETE FROM knowledge_red_flag WHERE code LIKE 'RF-TEST%'");
        jdbc.execute("DELETE FROM knowledge_grading_rule WHERE code LIKE 'GR-TEST%'");
        jdbc.execute("DELETE FROM knowledge_guard_term WHERE term LIKE '测试%'");
        jdbc.execute("UPDATE knowledge_switch SET enabled = 0 WHERE code = 'force_rule_only'");
    }

    // ------------------------------------------------------------ 鉴权

    @Test
    @DisplayName("C 端令牌调不了运营接口：登录域不一致等于没登录（ADR-0012）")
    void cEndTokenCannotReachAdminEndpoints() {
        String cEndToken = api.registerAndGetAccessToken(nextPhone());

        assertThat(api.get("/api/v1/admin/ai/prompts", cEndToken).code()).isEqualTo(40100);
        assertThat(api.get("/api/v1/admin/ai/prompts", null).code()).isEqualTo(40100);
        assertThat(api.post("/api/v1/admin/ai/red-flags",
                Map.of("code", "RF-TEST1", "pattern", "测试", "level", 3, "action_hint", "立即送医",
                        "enabled", true), cEndToken).code())
                .as("写接口同样要挡住").isEqualTo(40100);
    }

    // ------------------------------------------------------------ 提示词

    @Test
    @DisplayName("提示词：新增版本 / 判重 / 灰度调整（回滚不发版），并留下 operator 与 trace")
    void promptVersionLifecycle() {
        String admin = adminToken();

        ApiClient.ApiCall created = api.post("/api/v1/admin/ai/prompts", prompt("p-test1", 100), admin);
        assertCodeOk(created, "新建提示词版本");
        long id = created.data().path("id").asLong();
        assertThat(created.data().path("version").asText()).isEqualTo("p-test1");
        assertThat(created.data().path("review_status").asText())
                .as("新建的提示词一律待复核：内容权威性由人评审产出，不由代码盖章")
                .isEqualTo("pending_review");

        // 同版本号再发一次 → 40900：留痕按版本归因，同号覆盖会让归因失去对照物
        assertThat(api.post("/api/v1/admin/ai/prompts", prompt("p-test1", 50), admin).code())
                .isEqualTo(40900);

        // 灰度调整 = 回滚动作：出问题把比例改回 0，不发版
        ApiClient.ApiCall updated = api.put("/api/v1/admin/ai/prompts/" + id,
                Map.of("gray_ratio", 0, "enabled", true, "remark", "回滚到不生效"), admin);
        assertCodeOk(updated, "调整灰度");
        assertThat(updated.data().path("gray_ratio").asInt()).isZero();

        ApiClient.ApiCall listed = api.get("/api/v1/admin/ai/prompts", admin);
        assertCodeOk(listed, "提示词列表");
        assertThat(listed.data().toString()).contains("p-test1").contains("回滚到不生效");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT created_by, updated_by, trace_id FROM knowledge_prompt_template WHERE version = ?",
                "p-test1");
        assertThat(((Number) row.get("created_by")).longValue())
                .as("写操作要留 operator_id（ADR-0011）").isPositive();
        assertThat(row.get("trace_id").toString())
                .as("写操作要留 trace_id，才能与日志串起来").isNotBlank();
        assertThat(((Number) row.get("updated_by")).longValue()).isPositive();
    }

    // ------------------------------------------------------------ 红线词

    @Test
    @DisplayName("红线词：可增、可停用、可软删；停用后 Python 侧读不到它（enabled=0）")
    void redFlagCrud() {
        String admin = adminToken();

        ApiClient.ApiCall created = api.post("/api/v1/admin/ai/red-flags",
                Map.of("code", "RF-TEST1", "pattern", "测试中毒词",
                        "variants", List.of("吃了测试的东西"), "species_scope", "all",
                        "age_stage_scope", "all", "level", 3, "action_hint", "立即送医，不要自行处理。",
                        "enabled", true, "remark", "运营新增的测试规则"), admin);
        assertCodeOk(created, "新增红线");
        long id = created.data().path("id").asLong();
        assertThat(created.data().path("variants").get(0).asText()).isEqualTo("吃了测试的东西");

        assertThat(api.post("/api/v1/admin/ai/red-flags",
                Map.of("code", "RF-TEST1", "pattern", "重复编号", "level", 3,
                        "action_hint", "x", "enabled", true), admin).code())
                .as("编号是留痕引用的键，重复会让两次命中分不开").isEqualTo(40900);

        ApiClient.ApiCall disabled = api.put("/api/v1/admin/ai/red-flags/" + id,
                Map.of("code", "RF-TEST1", "pattern", "测试中毒词", "variants", List.of(),
                        "level", 3, "action_hint", "立即送医，不要自行处理。", "enabled", false), admin);
        assertCodeOk(disabled, "停用红线");
        assertThat(jdbc.queryForMap("SELECT enabled FROM knowledge_red_flag WHERE id = ?", id)
                .get("enabled")).isEqualTo(0);

        // 删除是**软删**：留痕里引用过它的咨询仍要能查到当时的规则（V6 的 is_deleted）
        assertCodeOk(api.delete("/api/v1/admin/ai/red-flags/" + id, admin), "删除红线");
        assertThat(jdbc.queryForMap("SELECT is_deleted FROM knowledge_red_flag WHERE id = ?", id)
                .get("is_deleted")).isEqualTo(1);
        assertThat(api.delete("/api/v1/admin/ai/red-flags/99999999", admin).code())
                .as("删不存在的规则 → 40400").isEqualTo(40400);
    }

    // ------------------------------------------------------------ 分级规则与护栏词表

    @Test
    @DisplayName("分级规则与护栏词表：都能增改，编号/词条重复给 40900")
    void gradingRuleAndGuardTermCrud() {
        String admin = adminToken();

        ApiClient.ApiCall rule = api.post("/api/v1/admin/ai/grading-rules",
                Map.of("code", "GR-TEST1", "name", "测试规则", "match_terms", List.of("测试症状"),
                        "min_level", 2, "species_scope", "cat", "age_stage_scope", "all",
                        "advice", "建议尽快就医。", "enabled", true, "remark", "运营新增"), admin);
        assertCodeOk(rule, "新增分级规则");
        long ruleId = rule.data().path("id").asLong();
        assertThat(rule.data().path("match_terms").get(0).asText()).isEqualTo("测试症状");
        assertThat(rule.data().path("min_level").asInt()).isEqualTo(2);

        ApiClient.ApiCall ruleUpdated = api.put("/api/v1/admin/ai/grading-rules/" + ruleId,
                Map.of("code", "GR-TEST1", "name", "测试规则（改）", "match_terms", List.of("测试症状", "另一个词"),
                        "min_level", 3, "species_scope", "cat", "age_stage_scope", "all",
                        "advice", "立即送医。", "enabled", true), admin);
        assertCodeOk(ruleUpdated, "修改分级规则");
        assertThat(jdbc.queryForMap("SELECT min_level, match_terms FROM knowledge_grading_rule WHERE id = ?",
                ruleId).get("min_level")).isEqualTo(3);

        Map<String, Object> guard = Map.of("kind", "drug", "term", "测试新药", "note", "运营加的测试药名",
                "enabled", true);
        assertCodeOk(api.post("/api/v1/admin/ai/guard-terms", guard, admin), "新增护栏词");
        assertThat(api.post("/api/v1/admin/ai/guard-terms", guard, admin).code())
                .as("同类同词重复 → 40900").isEqualTo(40900);

        ApiClient.ApiCall terms = api.get("/api/v1/admin/ai/guard-terms?kind=drug", admin);
        assertCodeOk(terms, "护栏词列表");
        assertThat(terms.data().toString()).contains("测试新药");

        // 运营词表里填一个错类型：40001（参数错误），不要静默当成合法配置
        assertThat(api.post("/api/v1/admin/ai/guard-terms",
                Map.of("kind", "unknown", "term", "测试词", "enabled", true), admin).code())
                .isEqualTo(40001);
    }

    // ------------------------------------------------------------ 降级开关

    @Test
    @DisplayName("降级开关：可以一键切到纯规则通道；不存在的开关给 40400（不许新造没人读的闸门）")
    void killSwitch() {
        String admin = adminToken();

        List<String> codes = api.get("/api/v1/admin/ai/switches", admin).data().findValuesAsText("code");
        assertThat(codes).contains("force_rule_only", "retrieval_enabled", "retrieval_strict");

        assertCodeOk(api.put("/api/v1/admin/ai/switches/force_rule_only", Map.of("enabled", true), admin),
                "打开纯规则通道");
        assertThat(jdbc.queryForMap("SELECT enabled FROM knowledge_switch WHERE code = 'force_rule_only'")
                .get("enabled")).isEqualTo(1);

        assertThat(api.put("/api/v1/admin/ai/switches/not_a_switch", Map.of("enabled", true), admin).code())
                .as("代码里没人读的开关会让运营以为它生效了——比没有开关更糟")
                .isEqualTo(40400);
    }

    // ------------------------------------------------------------ 抽检

    @Test
    @DisplayName("抽检：按 prompt_version 归因分级漂移，且不下发问题原文")
    void consultSamplingByPromptVersion() {
        String admin = adminToken();
        String token = api.registerAndGetAccessToken(nextPhone());
        long petId = api.createPet(token, "豆豆");

        long oldId = insertConsult(petId, "p1", 2, 0);
        long newId = insertConsult(petId, "p2", 3, 1);

        ApiClient.ApiCall call = api.get("/api/v1/admin/ai/consults?prompt_version=p2", admin);
        assertCodeOk(call, "按 prompt_version 抽检");

        assertThat(call.data().path("total").asLong()).isEqualTo(1);
        var row = call.data().path("list").get(0);
        assertThat(row.path("id").asLong()).isEqualTo(newId);
        assertThat(row.path("prompt_version").asText()).isEqualTo("p2");
        assertThat(row.path("risk_level").asInt()).isEqualTo(3);
        assertThat(row.path("unvetted_hits").get(0).asText()).isEqualTo("K-0010");
        assertThat(row.has("question_enc"))
                .as("问题原文是病历口径的密文，解密给运营看属权限问题（未定）——不在这里下发")
                .isFalse();

        // 不带过滤条件时两条都在
        assertThat(api.get("/api/v1/admin/ai/consults", admin).data().path("total").asLong()).isEqualTo(2);
        assertThat(api.get("/api/v1/admin/ai/consults?degraded=true", admin).data().path("list")
                .get(0).path("id").asLong()).isEqualTo(newId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_consult WHERE id = ?", Long.class, oldId))
                .isEqualTo(1L);
    }

    // ------------------------------------------------------------ 分页口径

    /**
     * 与项目历史上的 D20 / QueryBoundary 同族：**「看起来正常但答案是错的」**。
     *
     * <p>丢 {@code Page.getTotal()} 之后，{@code total} 恒等于这一页的条数、{@code has_more} 恒为
     * false——第一页看着对，翻到第二页就发现漏了（运营会以为红线词表只有这么多条）。
     * 所以这里把 {@code total} 锚在库里的真实条数上，而不是锚在「这一页有几条」上。
     */
    @Test
    @DisplayName("红线词分页：total 是满足条件的总数、has_more 按总数算（不是一页的条数）")
    void redFlagPagingReportsRealTotal() {
        String admin = adminToken();
        for (int i = 1; i <= 3; i++) {
            assertCodeOk(api.post("/api/v1/admin/ai/red-flags",
                    Map.of("code", "RF-TESTP" + i, "pattern", "分页测试词" + i, "level", 3,
                            "action_hint", "立即送医。", "enabled", true), admin), "造分页数据");
        }
        long expectedTotal = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_red_flag WHERE is_deleted = 0", Long.class);

        ApiClient.ApiCall firstPage = api.get("/api/v1/admin/ai/red-flags?page=1&page_size=2", admin);
        assertCodeOk(firstPage, "第一页");
        assertThat(firstPage.data().path("total").asLong())
                .as("total 是库里的总数，不是这一页的条数").isEqualTo(expectedTotal);
        assertThat(firstPage.data().path("list").size()).as("每页 2 条").isEqualTo(2);
        assertThat(firstPage.data().path("has_more").asBoolean())
                .as("total > page*page_size，还有下一页").isTrue();

        long lastPage = (expectedTotal + 1) / 2;
        ApiClient.ApiCall tail = api.get("/api/v1/admin/ai/red-flags?page=" + lastPage + "&page_size=2", admin);
        assertCodeOk(tail, "末页");
        assertThat(tail.data().path("total").asLong()).isEqualTo(expectedTotal);
        assertThat(tail.data().path("has_more").asBoolean()).as("末页之后没有了").isFalse();

        ApiClient.ApiCall beyond = api.get("/api/v1/admin/ai/red-flags?page=" + (lastPage + 1) + "&page_size=2", admin);
        assertCodeOk(beyond, "翻过末页");
        assertThat(beyond.data().path("list").size()).isZero();
        assertThat(beyond.data().path("total").asLong())
                .as("翻过头了 total 仍然是库里的数——丢 total 的实现这里最藏不住").isEqualTo(expectedTotal);
    }

    // ------------------------------------------------------------ enabled 的形状

    /**
     * 裁定（本轮）：**读写都用 JSON boolean**，DB 保持 tinyint（迁移与 Python 直读都不动）。
     *
     * <p>读侧早就是 boolean，写侧却是 0/1 整数——同一个字段两种形状，前端照着契约生成的类型
     * 会与运行时对不上。这条用例把两侧钉在一起：请求体收 {@code true}/{@code false}、
     * 过滤参数也收 {@code true}/{@code false}、返回的是 JSON boolean、库里落的是 tinyint。
     */
    @Test
    @DisplayName("enabled 读写同一形状：请求体与过滤参数都收 boolean，返回也是 boolean")
    void enabledIsBooleanOnBothSides() {
        String admin = adminToken();

        // 写：红线词
        ApiClient.ApiCall created = api.post("/api/v1/admin/ai/red-flags",
                Map.of("code", "RF-TESTB1", "pattern", "布尔口径测试词", "level", 3,
                        "action_hint", "立即送医。", "enabled", false), admin);
        assertCodeOk(created, "新增一条停用的红线");
        assertThat(created.data().path("enabled").isBoolean())
                .as("读侧下发的是 JSON boolean，不是 0/1").isTrue();
        assertThat(created.data().path("enabled").asBoolean()).isFalse();
        assertThat(jdbc.queryForMap("SELECT enabled FROM knowledge_red_flag WHERE code = ?", "RF-TESTB1")
                .get("enabled")).as("库里仍是 tinyint：false 落 0").isEqualTo(0);

        // 读：过滤参数同一口径（写 0/1 在这里会被拒成 40001）
        ApiClient.ApiCall disabledOnly = api.get("/api/v1/admin/ai/red-flags?enabled=false&page_size=100", admin);
        assertCodeOk(disabledOnly, "按 enabled=false 过滤红线词");
        assertThat(disabledOnly.data().path("list").toString()).contains("RF-TESTB1");
        ApiClient.ApiCall enabledOnly = api.get("/api/v1/admin/ai/red-flags?enabled=true&page_size=100", admin);
        assertCodeOk(enabledOnly, "按 enabled=true 过滤红线词");
        assertThat(enabledOnly.data().path("list").toString())
                .as("停用的那条不该出现在启用的列表里").doesNotContain("RF-TESTB1");

        assertCodeOk(api.get("/api/v1/admin/ai/grading-rules?enabled=false", admin),
                "分级规则列表的过滤参数同一口径");

        // 写：提示词启停、护栏词、开关（其余三处）
        ApiClient.ApiCall prompt = api.post("/api/v1/admin/ai/prompts", prompt("p-testbool", 100), admin);
        assertCodeOk(prompt, "新建提示词版本");
        ApiClient.ApiCall promptOff = api.put("/api/v1/admin/ai/prompts/"
                        + prompt.data().path("id").asLong(),
                Map.of("gray_ratio", 0, "enabled", false), admin);
        assertCodeOk(promptOff, "停用提示词版本");
        assertThat(promptOff.data().path("enabled").isBoolean()).isTrue();
        assertThat(promptOff.data().path("enabled").asBoolean()).isFalse();
        assertThat(jdbc.queryForMap("SELECT enabled FROM knowledge_prompt_template WHERE version = ?", "p-testbool")
                .get("enabled")).isEqualTo(0);

        ApiClient.ApiCall term = api.post("/api/v1/admin/ai/guard-terms",
                Map.of("kind", "drug", "term", "测试布尔药名", "enabled", false), admin);
        assertCodeOk(term, "新增一条停用的护栏词");
        assertThat(term.data().path("enabled").isBoolean()).isTrue();
        assertThat(term.data().path("enabled").asBoolean()).isFalse();
        assertThat(jdbc.queryForMap("SELECT enabled FROM knowledge_guard_term WHERE term = ?", "测试布尔药名")
                .get("enabled")).isEqualTo(0);

        ApiClient.ApiCall switched = api.put("/api/v1/admin/ai/switches/force_rule_only",
                Map.of("enabled", true), admin);
        assertCodeOk(switched, "打开降级开关");
        assertThat(switched.data().path("enabled").isBoolean()).isTrue();
        assertThat(switched.data().path("enabled").asBoolean()).isTrue();
        assertThat(jdbc.queryForMap("SELECT enabled FROM knowledge_switch WHERE code = 'force_rule_only'")
                .get("enabled")).isEqualTo(1);
    }

    // ------------------------------------------------------------ 工具定义的下发

    /**
     * 库里 {@code tool_schema} 是 NOT NULL 且创建必传，读接口却不给——运营看得到提示词正文、
     * 看不到与它同源的工具定义，而两者不匹配正是「模型不按格式上报」的原因之一。
     * 它**只读**：工具定义的改动走发版（ADR-0010 的代码常量那一层）。
     */
    @Test
    @DisplayName("提示词读接口下发 tool_schema（只读：运营看不到已生效的工具定义等于没有）")
    void promptViewCarriesToolSchema() {
        String admin = adminToken();
        String expected = "{\"type\":\"function\",\"function\":{\"name\":\"report_triage\"}}";

        ApiClient.ApiCall created = api.post("/api/v1/admin/ai/prompts", prompt("p-testschema", 0), admin);
        assertCodeOk(created, "新建提示词版本");
        assertThat(created.data().has("tool_schema"))
                .as("新建的响应要把它带回来，否则运营刚提交的定义立刻就看不见了").isTrue();
        assertJsonEquals(created.data().path("tool_schema"), expected);

        ApiClient.ApiCall listed = api.get("/api/v1/admin/ai/prompts?code=triage", admin);
        assertCodeOk(listed, "提示词列表");
        var row = findVersion(listed.data(), "p-testschema");
        assertThat(row).as("列表里能找到刚建的版本").isNotNull();
        assertThat(row.has("tool_schema")).as("列表也要下发 tool_schema").isTrue();
        assertJsonEquals(row.path("tool_schema"), expected);
    }

    // ------------------------------------------------------------ 死代码的护栏

    /**
     * {@code code} 的「不传就用默认值 triage」这个意图**由契约的 required 表达**，
     * 不在 service 里再留一个回落分支（那个分支不可达：{@code @NotBlank} 在 service 之前就拦了）。
     * 这条用例同时钉住两件事：不传 code 报 40001，且**不会**落一行 triage 的模板。
     */
    @Test
    @DisplayName("新建提示词必须带 code：不传报 40001，不回落到 triage（默认值由契约表达）")
    void promptWithoutCodeIsRejectedAndNothingIsWritten() {
        String admin = adminToken();
        ApiClient.ApiCall call = api.post("/api/v1/admin/ai/prompts",
                Map.of("version", "p-testnocode", "system_prompt", "正文",
                        "tool_schema", "{}", "gray_ratio", 0), admin);

        assertThat(call.code()).as("code 是必填：契约的 required 里写着").isEqualTo(40001);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_prompt_template WHERE version = ?",
                Long.class, "p-testnocode"))
                .as("被拒的请求不该留下任何行").isZero();
    }

    // ------------------------------------------------------------ 工具

    // 注意：下面这些请求体用 Map 直接写，**键名必须是 snake_case**——全局 Jackson 命名策略
    // 是 SNAKE_CASE（ADR-0011），用 camelCase 会让字段「没传」，表现为 40001「XX 不能为空」。
    // 用 DTO 记录（如 prompt(...)）时序列化会自动带上下划线，不需要手写。

    private static AiOpsDtos.PromptTemplateRequest prompt(String version, int grayRatio) {
        return new AiOpsDtos.PromptTemplateRequest(
                "triage", version, "你是一只宠物健康分诊助手。",
                "{\"type\":\"function\",\"function\":{\"name\":\"report_triage\"}}",
                grayRatio, "测试用提示词版本");
    }

    /** 从列表响应里挑出某个版本的行（列表按版本号倒序，顺序不是断言对象）。 */
    private static JsonNode findVersion(JsonNode list, String version) {
        for (JsonNode node : list) {
            if (version.equals(node.path("version").asText())) {
                return node;
            }
        }
        return null;
    }

    /**
     * 按 JSON 结构比，不按字符串比：{@code tool_schema} 列是 MySQL 的 JSON 类型，
     * 落库时会把空白重排（{@code {"a":1}} → {@code {"a": 1}}）——比字符串会把
     * 「同一份定义」误报成不一致，那是测试在挑库的格式，不是在挑接口。
     */
    private static void assertJsonEquals(JsonNode actual, String expectedJson) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            assertThat(mapper.readTree(actual.asText()))
                    .as("下发的应当就是提交的那份工具定义")
                    .isEqualTo(mapper.readTree(expectedJson));
        } catch (JsonProcessingException e) {
            throw new AssertionError("下发的不是合法 JSON：" + actual, e);
        }
    }

    /** 直接插一行留痕（抽检接口的输入）：走 jdbc 而不是打模型，抽检本身与生成无关。 */
    private long insertConsult(long petId, String promptVersion, int riskLevel, int degraded) {
        jdbc.update("""
                INSERT INTO ai_consult (user_id, pet_id, trace_id, question_enc, image_count, risk_level,
                                        need_hospital, red_flag_hits, guard_hits, red_flag_check, degraded,
                                        prompt_version, retrieval_check, unvetted_hits, citations,
                                        model_name, model_version, latency_ms)
                VALUES (1, ?, 'trace-test', 'enc', 0, ?, 1, NULL, NULL, 'ok', ?, ?, 'ok',
                        '["K-0010"]', NULL, 'stub', 'stub', 10)
                """, petId, riskLevel, degraded, promptVersion);
        return jdbc.queryForObject("SELECT MAX(id) FROM ai_consult", Long.class);
    }

    // ------------------------------------------------------------ 知识条目复核（D-12）

    /** 本类复核过的条目编号：用例结束后复位回 pending_review（那 40 条种子是别的切片的输入）。 */
    private final List<String> reviewedCodes = new java.util.ArrayList<>();

    @AfterEach
    void restoreKnowledgeEntries() {
        for (String code : reviewedCodes) {
            jdbc.update("UPDATE knowledge_entry SET review_status = 'pending_review', reviewed_by = NULL, "
                    + "reviewed_credential = NULL, reviewed_at = NULL WHERE code = ?", code);
        }
        reviewedCodes.clear();
    }

    @Test
    @DisplayName("知识条目：列表给复核要看的字段（不含正文），能按状态与关键词筛")
    void knowledgeEntriesAreListable() {
        String admin = adminToken();

        ApiClient.ApiCall all = api.get("/api/v1/admin/ai/knowledge-entries?page_size=100", admin);
        assertCodeOk(all, "知识条目列表");
        // 种子 40 条，且默认**未复核在前**（运营打开就想看到还差哪些）
        assertThat(all.data().path("total").asLong()).isGreaterThanOrEqualTo(40L);
        JsonNode first = all.data().path("list").get(0);
        assertThat(first.path("code").asText()).startsWith("K-");
        assertThat(first.path("review_status").asText()).isEqualTo("pending_review");
        assertThat(first.has("body")).as("正文不进列表（那是检索素材）").isFalse();
        assertThat(first.has("structured_payload")).isFalse();

        // 按状态筛：种子里一条 vetted 都没有 → 空列表；按关键词筛：命中标题或摘要
        ApiClient.ApiCall vetted = api.get("/api/v1/admin/ai/knowledge-entries?review_status=vetted", admin);
        assertCodeOk(vetted, "只看已复核");
        assertThat(vetted.data().path("total").asLong()).isZero();
        // 中文关键词**直接拼进 URL**（与其它用例一致）：这里再 URLEncoder 一次会被客户端二次编码，
        // 服务端收到的是字面的 `%E7%8A%AC`，LIKE 自然一条也命中不了
        String keyword = first.path("title").asText().substring(0, 2);
        ApiClient.ApiCall searched = api.get("/api/v1/admin/ai/knowledge-entries?keyword=" + keyword, admin);
        assertCodeOk(searched, "按关键词筛");
        assertThat(searched.data().path("total").asLong()).isPositive();

        // 登录域：C 端令牌与匿名都进不来（ADR-0012）
        assertThat(api.get("/api/v1/admin/ai/knowledge-entries", api.registerAndGetAccessToken(nextPhone())).code())
                .isEqualTo(40100);
        assertThat(api.get("/api/v1/admin/ai/knowledge-entries", null).code()).isEqualTo(40100);
    }

    @Test
    @DisplayName("复核：通过要落「谁 + 什么资质 + 什么时候」，打回置回未复核并清掉那三列")
    void reviewRecordsWhoAndWhat() {
        String admin = adminToken();
        String code = jdbc.queryForObject("SELECT `code` FROM `knowledge_entry` "
                + "WHERE `review_status` = 'pending_review' ORDER BY `code` LIMIT 1", String.class);
        reviewedCodes.add(code);

        ApiClient.ApiCall vetted = api.post("/api/v1/admin/ai/knowledge-entries/" + code + "/review",
                Map.of("action", "vet", "reviewer", "李兽医", "credential", "执业兽医师，证号 A1234"), admin);
        assertCodeOk(vetted, "复核通过");
        assertThat(vetted.data().path("review_status").asText()).isEqualTo("vetted");
        assertThat(vetted.data().path("reviewed_by").asText()).isEqualTo("李兽医");
        assertThat(vetted.data().path("reviewed_credential").asText()).contains("A1234");
        assertThat(vetted.data().path("reviewed_at").isNull()).isFalse();

        // 库里也对得上，而且写操作留了痕（operator_id 与 trace_id，ADR-0011）
        Map<String, Object> row = jdbc.queryForMap("SELECT `review_status`, `reviewed_by`, "
                + "`reviewed_credential`, `reviewed_at`, `updated_by`, `trace_id` FROM `knowledge_entry` "
                + "WHERE `code` = ?", code);
        assertThat(row.get("review_status")).isEqualTo("vetted");
        assertThat(row.get("reviewed_by")).isEqualTo("李兽医");
        assertThat(row.get("reviewed_at")).isNotNull();
        assertThat(((Number) row.get("updated_by")).longValue()).as("留了 operator_id").isPositive();

        // 复核之后「已复核」筛得到它了：这就是「AI 引用为什么一直为空」的那一步
        ApiClient.ApiCall filtered = api.get("/api/v1/admin/ai/knowledge-entries?review_status=vetted&page_size=100",
                admin);
        assertThat(filtered.data().path("list").findValuesAsText("code")).contains(code);

        // 打回：回到未复核，清掉复核人那三列（**两级模型不扩第三态**）
        ApiClient.ApiCall rejected = api.post("/api/v1/admin/ai/knowledge-entries/" + code + "/review",
                Map.of("action", "reject", "reviewer", "李兽医", "credential", "执业兽医师，证号 A1234"), admin);
        assertCodeOk(rejected, "打回");
        assertThat(rejected.data().path("review_status").asText()).isEqualTo("pending_review");
        assertThat(rejected.data().path("reviewed_by").isNull()).isTrue();
        assertThat(rejected.data().path("reviewed_at").isNull()).isTrue();

        // **库里也要真的清掉**：MyBatis-Plus 的 updateById 会跳过 null 字段，
        // 用它来「清掉复核人」是一次静默失败（返回的视图是空的，库里的名字还在）——
        // 这条断言就是为那个坑准备的
        Map<String, Object> afterReject = jdbc.queryForMap("SELECT `review_status`, `reviewed_by`, "
                + "`reviewed_credential`, `reviewed_at` FROM `knowledge_entry` WHERE `code` = ?", code);
        assertThat(afterReject.get("review_status")).isEqualTo("pending_review");
        assertThat(afterReject.get("reviewed_by")).as("打回后库里不该还留着上一位复核者").isNull();
        assertThat(afterReject.get("reviewed_at")).isNull();
    }

    @Test
    @DisplayName("复核的参数与对象都要挡住：动作非法 / 缺复核人 / 条目不存在")
    void reviewValidatesInput() {
        String admin = adminToken();
        String code = jdbc.queryForObject("SELECT `code` FROM `knowledge_entry` ORDER BY `code` LIMIT 1", String.class);

        // 动作只有 vet / reject（没有任何「直接把状态改成某字符串」的口子）
        assertThat(api.post("/api/v1/admin/ai/knowledge-entries/" + code + "/review",
                Map.of("action", "vetted", "reviewer", "李兽医", "credential", "执业兽医师"), admin).code())
                .as("动作必须是 vet / reject").isEqualTo(40001);
        // 复核人与资质必填：vetted 是专业背书，不是一次开关操作（ADR-0040 第二节）
        assertThat(api.post("/api/v1/admin/ai/knowledge-entries/" + code + "/review",
                Map.of("action", "vet", "credential", "执业兽医师"), admin).code()).isEqualTo(40001);
        assertThat(api.post("/api/v1/admin/ai/knowledge-entries/" + code + "/review",
                Map.of("action", "vet", "reviewer", "李兽医"), admin).code()).isEqualTo(40001);
        // 不存在的编号
        assertThat(api.post("/api/v1/admin/ai/knowledge-entries/K-9999/review",
                Map.of("action", "vet", "reviewer", "李兽医", "credential", "执业兽医师"), admin).code())
                .isEqualTo(40400);
    }
    // ------------------------------------------------------------ AI 用量（D-28：成本测算的只读那一半）

    /** 造一条用完量的咨询：token 与模型由调用方给（本类只关心聚合，不关心回答内容）。 */
    private void insertUsage(String modelName, String modelVersion, int promptTokens, int completionTokens,
                             boolean redFlag, boolean degraded, java.time.LocalDateTime createdAt) {
        jdbc.update("""
                INSERT INTO ai_consult (user_id, pet_id, trace_id, question_enc, image_count, risk_level,
                                        need_hospital, red_flag_hits, red_flag_check, degraded,
                                        model_name, model_version, prompt_version, latency_ms,
                                        prompt_tokens, completion_tokens, created_at, updated_at)
                VALUES (1, 1, 'trace-usage', 'enc', 0, 2, 0, ?, 'ok', ?, ?, ?, 'p-test', 10, ?, ?, ?, ?)
                """, redFlag ? "[\"RF-001\"]" : null, degraded ? 1 : 0,
                modelName, modelVersion, promptTokens, completionTokens, createdAt, createdAt);
    }

    @Test
    @DisplayName("AI 用量：按账期 × 模型聚合真实 token，红线短路与降级各计一行，合计对得上")
    void usageIsAggregatedByModel() {
        String admin = adminToken();
        String period = "2026-03";
        var day = java.time.LocalDateTime.of(2026, 3, 15, 10, 0);
        insertUsage("deepseek-chat", "v3", 1000, 200, false, false, day);
        insertUsage("deepseek-chat", "v3", 500, 100, false, true, day);          // 降级的那条
        insertUsage("rule:red_flag", "v1", 0, 0, true, false, day);              // 红线短路：根本没调模型
        insertUsage("deepseek-vl", "v1", 300, 50, false, false, day.plusDays(1));
        // 账期之外的一条：不该出现在 2026-03 里
        insertUsage("deepseek-chat", "v3", 9999, 9999, false, false, day.plusMonths(1));

        JsonNode usage = api.get("/api/v1/admin/ai/usage?period=" + period, admin).data();
        assertThat(usage.path("period").asText()).isEqualTo(period);

        JsonNode chat = modelRow(usage, "deepseek-chat", "v3");
        assertThat(chat.path("calls").asLong()).isEqualTo(2);
        assertThat(chat.path("prompt_tokens").asLong()).isEqualTo(1500);
        assertThat(chat.path("completion_tokens").asLong()).isEqualTo(300);
        assertThat(chat.path("degraded_calls").asLong()).as("两条里有一条降级").isEqualTo(1);
        assertThat(chat.path("red_flag_calls").asLong()).isZero();

        JsonNode rule = modelRow(usage, "rule:red_flag", "v1");
        assertThat(rule.path("red_flag_calls").asLong()).as("短路的那条记在红线列上").isEqualTo(1);
        assertThat(rule.path("prompt_tokens").asLong()).as("没调模型就没有 token").isZero();

        // 合计行 = 各模型行相加（页面上直接显示它，不再自己算）
        JsonNode totals = usage.path("totals");
        assertThat(totals.path("model_name").asText()).isEqualTo("合计");
        assertThat(totals.path("calls").asLong()).isEqualTo(4);
        assertThat(totals.path("prompt_tokens").asLong()).isEqualTo(1800);
        assertThat(totals.path("completion_tokens").asLong()).isEqualTo(350);

        // 契约里没有金额字段：这一页只摊开用量，单价由页面上填（ADR-0050 第五节）
        assertThat(chat.has("cost")).isFalse();
        assertThat(chat.has("amount")).isFalse();

        // 默认按当月：不传 period 也返回一个合法账期
        assertThat(api.get("/api/v1/admin/ai/usage", admin).data().path("period").asText())
                .matches("\\d{4}-\\d{2}");
        // 格式不对：40001；C 端令牌：40100
        assertThat(api.get("/api/v1/admin/ai/usage?period=2026/03", admin).code()).isEqualTo(40001);
        assertThat(api.get("/api/v1/admin/ai/usage", api.registerAndGetAccessToken(nextPhone())).code())
                .isEqualTo(40100);
    }

    private static JsonNode modelRow(JsonNode usage, String modelName, String modelVersion) {
        for (JsonNode row : usage.path("models")) {
            if (modelName.equals(row.path("model_name").asText())
                    && modelVersion.equals(row.path("model_version").asText())) {
                return row;
            }
        }
        throw new AssertionError("用量里没有 " + modelName + " / " + modelVersion + "：" + usage);
    }

    @AfterEach
    void cleanUsageRows() {
        jdbc.execute("DELETE FROM ai_consult WHERE trace_id = 'trace-usage'");
    }
}
