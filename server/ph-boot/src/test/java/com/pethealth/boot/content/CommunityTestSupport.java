package com.pethealth.boot.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethealth.account.auth.JwtService;
import com.pethealth.boot.support.ApiClient;
import com.pethealth.boot.support.IntegrationTestBase;
import com.pethealth.common.security.LoginDomain;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 社区（经验卡片 + 问答互助，切片 #84 / F020）集成测试的公共基座。
 *
 * <p>三件事在这里备好，用例里不再重复：
 *
 * <ol>
 *   <li><b>造人</b>：{@link #createActor()} 注册一个 C 端账号并拿到令牌；后台令牌由
 *       {@link #adminToken()} 直接签发（运营后台的登录入口还没实现，与既有切片同一手法：
 *       鉴权链路完全一致，只有登录入口是绕过的）；
 *   <li><b>发文权益</b>：{@link #grantPosting} 走**真实路径** `POST /admin/rights/grants`
 *       授予 {@code community.post}（不是往库里插一行）——权益判定由 ph-privilege 实时算，
 *       所以这里的用例同时验证了那一条链路是通的；
 *   <li><b>清库</b>：本切片的四张表 + {@code rights_grant} 每个用例从零开始。
 *       **词表的六个种子词不清**（它们是迁移插的种子），只删测试自己加的词并把启停复位——
 *       清了它们，「机审能拦人」这条用例就失去了参照物。
 * </ol>
 *
 * <p>为什么清 {@code rights_grant} 而不仅仅是本切片的表：不发权益是这一块**最主要**的一条拒绝路径，
 * 而它是「查库判定」的。上一用例留下的授予会让下一个用例的 40300 断言悄悄变成 200。
 */
public abstract class CommunityTestSupport extends IntegrationTestBase {

    private static final AtomicInteger PHONE_SEQ = new AtomicInteger(0);

    protected ApiClient api;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUpCommunityApi() {
        api = new ApiClient(rest, objectMapper);
        cleanCommunityData();
    }

    private void cleanCommunityData() {
        // 顺序：先子后主（本切片全是逻辑引用，没有物理外键，但这个顺序读起来最清楚）
        jdbc.execute("DELETE FROM `community_card_like`");
        jdbc.execute("DELETE FROM `community_card`");
        jdbc.execute("DELETE FROM `community_answer`");
        jdbc.execute("DELETE FROM `community_question`");
        jdbc.execute("DELETE FROM `rights_grant`");
        jdbc.execute("DELETE FROM `content_sensitive_word` WHERE `word` NOT IN "
                + "('加微信','加v','私信我','代购','包治百病','祖传秘方')");
        jdbc.execute("UPDATE `content_sensitive_word` SET `enabled` = 1");
    }

    // ---------------------------------------------------------------- 身份

    /** 一个唯一的测试手机号（11 位）。 */
    protected String nextPhone() {
        int seq = PHONE_SEQ.incrementAndGet() % 100_000_000;
        return "137" + String.format("%08d", (int) ((System.nanoTime() / 1000 + seq) % 100_000_000));
    }

    /**
     * 注册一个 C 端账号——**并回收那条「注册即得」的基线权益**。
     *
     * <p>注册流程从本轮起会在注册事务里授予一条平台默认的 {@code community.post}
     * （交付文档 2.2 的权限矩阵：发布内容 / 评论 = 注册用户 ✓；实现在 ph-account 的
     * {@code BaselineRights}）。于是「刚注册的账号没有发文权益」这个前提**不再天然成立**，
     * 而本夹具要的正是「一个普通账号（没有任何额外授予）」。
     *
     * <p>所以这里把基线那一条删掉，而不是改那两条断言：发文门禁的用例要证明的是
     * **门开着与门不拦是两件事**——门（40300）仍然拦得住没有权益的账号，
     * 而「注册就发内容」能不能走通由 account 侧的用例证明
     * （{@code BaselineCommunityPostRightTest}，它从注册一路跑到发卡片成功）。
     * 授予一律走真实接口（{@link #grantPosting}）；这里删的是夹具前提，不是被测行为。
     */
    protected Actor createActor() {
        ApiClient.ApiCall call = api.register(nextPhone());
        if (call.code() != 0) {
            throw new AssertionError("注册应当成功，实际：" + call.body());
        }
        Actor actor = new Actor(call.data().path("user").path("id").asLong(),
                call.data().path("access_token").asText());
        jdbc.update("DELETE FROM `rights_grant` WHERE `user_id` = ? AND `code` = 'community.post'",
                actor.userId());
        return actor;
    }

    /** 运营后台令牌（主体是一个新注册的账号；运营与超管在这一层还没区分）。 */
    protected String adminToken() {
        ApiClient.ApiCall call = api.register(nextPhone());
        if (call.code() != 0) {
            throw new AssertionError("注册应当成功，实际：" + call.body());
        }
        return jwtService.issue(call.data().path("user").path("id").asLong(), LoginDomain.ADMIN).token();
    }

    /** 授予社区发帖权益（走运营后台的真实接口，不是插库）。 */
    protected void grantPosting(Actor actor) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user_id", actor.userId());
        body.put("code", "community.post");
        body.put("source", 4);
        body.put("remark", "集成测试授予");
        ApiClient.ApiCall call = api.post("/api/v1/admin/rights/grants", body, adminToken());
        if (call.code() != 0) {
            throw new AssertionError("授予社区发帖权益应当成功，实际：" + call.body());
        }
    }

    /** 有发帖权益的 C 端用户。 */
    protected Actor postingActor() {
        Actor actor = createActor();
        grantPosting(actor);
        return actor;
    }

    // ---------------------------------------------------------------- 造数据

    /** 建一只宠物，返回 pet_id。 */
    protected long createPet(Actor actor, String name) {
        return api.createPet(actor.token(), name);
    }

    /** 一键生成卡片（原始请求体由用例拼：有的用例要故意带敏感词或错字段）。 */
    protected ApiClient.ApiCall createCard(Actor actor, Map<String, Object> body) {
        return api.post("/api/v1/app/community/cards", body, actor.token());
    }

    /** 卡片请求体的最小形状（source_type=1 打卡）。 */
    protected Map<String, Object> cardBody(long petId, String title, String content) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pet_id", petId);
        body.put("source_type", 1);
        body.put("source_ref", "9001");
        body.put("title", title);
        body.put("content", content);
        return body;
    }

    /** 建一张卡片并断言成功，返回 card_id。 */
    protected long cardOk(Actor actor, long petId, String title, String content) {
        ApiClient.ApiCall call = createCard(actor, cardBody(petId, title, content));
        assertOk(call, "生成卡片");
        return call.data().path("id").asLong();
    }

    /** 提问并断言成功，返回 question_id。 */
    protected long questionOk(Actor actor, String title, String content) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("content", content);
        ApiClient.ApiCall call = api.post("/api/v1/app/community/questions", body, actor.token());
        assertOk(call, "提问");
        return call.data().path("id").asLong();
    }

    /** 回答并断言成功，返回 answer_id。 */
    protected long answerOk(Actor actor, long questionId, String content) {
        ApiClient.ApiCall call = api.post(
                "/api/v1/app/community/questions/" + questionId + "/answers",
                Map.of("content", content), actor.token());
        assertOk(call, "回答");
        return call.data().path("id").asLong();
    }

    // ---------------------------------------------------------------- 运营动作

    /** 审核通过（content_type：1 卡片 / 2 提问 / 3 回答）。 */
    protected ApiClient.ApiCall approveContent(int contentType, long contentId) {
        return api.post("/api/v1/admin/community/contents/" + contentType + "/" + contentId + "/approve",
                null, adminToken());
    }

    /** 驳回 / 下架。 */
    protected ApiClient.ApiCall rejectContent(int contentType, long contentId, String reason) {
        return api.post("/api/v1/admin/community/contents/" + contentType + "/" + contentId + "/reject",
                Map.of("reason", reason), adminToken());
    }

    /** 加一个机审敏感词。 */
    protected ApiClient.ApiCall addSensitiveWord(String word) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("word", word);
        body.put("category", "测试");
        body.put("remark", "集成测试用词");
        return api.post("/api/v1/admin/community/sensitive-words", body, adminToken());
    }

    // ---------------------------------------------------------------- 断言辅助

    protected void assertOk(ApiClient.ApiCall call, String what) {
        if (call.code() != 0) {
            throw new AssertionError(what + "应当成功，实际：" + call.body());
        }
    }

    protected void assertCode(ApiClient.ApiCall call, int expectedCode, int expectedHttpStatus, String what) {
        if (call.code() != expectedCode || call.status() != expectedHttpStatus) {
            throw new AssertionError(what + "应当返回 code=" + expectedCode + " http=" + expectedHttpStatus
                    + "，实际：http=" + call.status() + " body=" + call.body());
        }
    }

    protected Map<String, Object> cardRow(long cardId) {
        return jdbc.queryForMap("SELECT * FROM `community_card` WHERE `id` = ?", cardId);
    }

    /** C 端用户：账号 id + 它的 C 端令牌。 */
    public record Actor(long userId, String token) {
    }
}
