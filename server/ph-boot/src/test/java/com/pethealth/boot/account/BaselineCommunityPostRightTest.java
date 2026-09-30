package com.pethealth.boot.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethealth.account.auth.JwtService;
import com.pethealth.boot.support.ApiClient;
import com.pethealth.boot.support.IntegrationTestBase;
import com.pethealth.common.security.LoginDomain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **「注册即可发布」这条口径的端到端用例**（交付文档 2.2 的权限矩阵第 2 行：
 * 发布内容 / 评论 = 注册用户 ✓；ADR-0041 第一节把发文挂到权益码 {@code community.post} 上）。
 *
 * <p>为什么值得单独跑一条从注册到发帖的用例：社区那道门（40300）**曾经关着且没人能开**——
 * 邀请阶梯没配 {@code rights_code}、打卡不发权益、注册也不发，于是新用户注册完做不出任何
 * 能让自己有权益的动作。契约与代码各自都对，缺的是**那条路径**，而路径的缺失只有端到端跑一次才看得见
 * （单测两边都绿：门禁测的是「没有权益就拦」，权益引擎测的是「授予了就生效」）。
 *
 * <p><b>全程真实 HTTP、零桩</b>：注册（{@code POST /auth/register}，注册事务里授予基线权益）→
 * 建档（要一只自己的宠物）→ **发一张社区卡片成功**。中间没有任何一处绕过权益判定：
 * {@code ph-content} 的 {@code PostingAccess} 读的是 ph-privilege 实时算出来的判定。
 *
 * <p>三条断言各钉一件事：
 *
 * <ol>
 *   <li>注册后 {@code GET /rights} 里 {@code community.post} 生效——来源是
 *       **运营补偿(4) 且永久**（基线用哪个来源的理由见 {@code BaselineRights}）：
 *       来源必须是四项里优先级最低的那个，否则用户后来靠邀请 / 订阅挣到的同码权益会被基线盖住，
 *       真正的来源信息就丢了（ADR-0045 第二节）；
 *   <li>发卡片返回 200 + code 0，且卡片是**待审**（进审核是另一条硬口径，ADR-0041 第一节）；
 *   <li>运营回收那条基线之后，同一个账号再发就回到 **40300**——门开着与门不拦是两件事，
 *       这一条证明 200 是那条授予带来的，而不是门被拆了。
 * </ol>
 */
@DisplayName("账号 · 注册基线权益（community.post：注册即可发布）")
class BaselineCommunityPostRightTest extends IntegrationTestBase {

    private static final String CODE = "community.post";
    private static final AtomicInteger PHONE_SEQ = new AtomicInteger(0);

    private ApiClient api;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUpBaseline() {
        api = new ApiClient(rest, objectMapper);
        // 这两张表不在 IntegrationTestBase 的清理名单里（共享基类，跨切片），本用例自己清
        jdbc.execute("DELETE FROM `community_card`");
        jdbc.execute("DELETE FROM `rights_grant`");
    }

    @Test
    @DisplayName("注册 → 发一张社区卡片成功；运营回收基线后同一账号回到 40300")
    void registrationOpensTheCommunityDoor() {
        // ---- 1) 注册（基线权益在注册事务里授予，见 BaselineRights）
        String phone = nextPhone();
        ApiClient.ApiCall registration = api.register(phone);
        assertThat(registration.code()).isZero();
        String token = registration.data().path("access_token").asText();
        long userId = registration.data().path("user").path("id").asLong();

        // 基线在库里只有一条，且形状固定（来源 4 + 无到期 + 固定的 source_ref）
        Map<String, Object> grant = jdbc.queryForMap(
                "SELECT `source`, `expire_at`, `source_ref`, `status` FROM `rights_grant` "
                        + "WHERE `user_id` = ? AND `code` = ?", userId, CODE);
        assertThat(((Number) grant.get("source")).intValue()).isEqualTo(4);
        assertThat(grant.get("expire_at")).isNull();
        assertThat(grant.get("source_ref")).isEqualTo("register:baseline");
        assertThat(((Number) grant.get("status")).intValue()).isEqualTo(1);

        // ---- 2) C 端的权益视图看得到它（判定是实时的，不是缓存）
        JsonNode rights = api.get("/api/v1/app/rights", token).data().path("rights");
        JsonNode posting = null;
        for (JsonNode item : rights) {
            if (CODE.equals(item.path("code").asText())) {
                posting = item;
            }
        }
        assertThat(posting).as("注册后 community.post 必须出现在权益判定里").isNotNull();
        assertThat(posting.path("effective").asBoolean()).isTrue();
        assertThat(posting.path("source").asInt()).isEqualTo(4);
        assertThat(posting.path("expire_at").isNull()).as("基线是永久的").isTrue();

        // ---- 3) 建档 + 发一张社区卡片：**这道门真的开了**
        long petId = api.createPet(token, "旺财");
        ApiClient.ApiCall card = api.post("/api/v1/app/community/cards", cardBody(petId), token);
        assertThat(card.code()).as("注册即可发布：发卡片应当成功，实际：" + card.body()).isZero();
        assertThat(card.data().path("id").asLong()).isPositive();
        assertThat(card.data().path("status").asInt())
                .as("发文仍进审核（ADR-0041 第一节）：门开了不等于直接可见")
                .isZero();

        // ---- 4) 门还是门：运营回收那条基线后，同一个账号立刻被拦（40300）
        long grantId = grantIdOf(userId);
        ApiClient.ApiCall revoked = api.delete("/api/v1/admin/rights/grants/" + grantId, adminToken());
        assertThat(revoked.code()).isZero();
        ApiClient.ApiCall denied = api.post("/api/v1/app/community/cards", cardBody(petId), token);
        assertThat(denied.code()).isEqualTo(40300);
        assertThat(denied.status()).isEqualTo(403);
        assertThat(denied.message()).contains("发帖权益");
    }

    // ---------------------------------------------------------------- 小工具

    /** 社区卡片请求体的最小形状（source_type=1 打卡；服务端不重读档案正文）。 */
    private Map<String, Object> cardBody(long petId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pet_id", petId);
        body.put("source_type", 1);
        body.put("source_ref", "9001");
        body.put("title", "注册后的第一张卡片");
        body.put("content", "今天开始记录饮水量的变化。");
        return body;
    }

    /** 那条基线授予的 id（走运营的授予记录接口取，不直接拼库里的 id）。 */
    private long grantIdOf(long userId) {
        ApiClient.ApiCall grants = api.get(
                "/api/v1/admin/rights/grants?user_id=" + userId + "&code=" + CODE, adminToken());
        assertThat(grants.code()).isZero();
        JsonNode list = grants.data().path("list");
        assertThat(list.size()).isEqualTo(1);
        return list.get(0).path("id").asLong();
    }

    /** 运营后台令牌（登录入口还没实现，与既有切片同一手法：鉴权链路一致，只有入口是绕过的）。 */
    private String adminToken() {
        ApiClient.ApiCall call = api.register(nextPhone());
        assertThat(call.code()).isZero();
        return jwtService.issue(call.data().path("user").path("id").asLong(), LoginDomain.ADMIN).token();
    }

    private String nextPhone() {
        int seq = PHONE_SEQ.incrementAndGet() % 100_000_000;
        return "136" + String.format("%08d", (int) ((System.nanoTime() / 1000 + seq) % 100_000_000));
    }
}
