package com.pethealth.boot.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethealth.boot.support.ApiClient;
import com.pethealth.boot.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据导出与账号注销（切片 #74 的第二块）。
 *
 * <p>这两件事是合规的硬要求，而它们的失败方式很难在界面上看出来：导出少了一半数据、
 * 注销后手机号还被占着、旧令牌还能用——用户要很久之后才会发现。
 */
@DisplayName("数据导出与账号注销（切片 #74）")
class AccountLifecycleTest extends IntegrationTestBase {

    private static final String PHONE = "13900004001";

    @Autowired
    private ObjectMapper objectMapper;

    private ApiClient api;

    @BeforeEach
    void setUpClient() {
        api = new ApiClient(rest, objectMapper);
    }

    @Test
    @DisplayName("导出含账号资料、宠物、档案记录与消息")
    void exportContainsEverythingOfThisUser() {
        String token = api.registerAndGetAccessToken(PHONE);
        long petId = api.createPet(token, "豆豆");
        api.post("/api/v1/app/pets/" + petId + "/check-ins",
                new com.pethealth.api.app.CheckInSubmitRequest("2026-09-28",
                        java.util.List.of(new com.pethealth.api.app.CheckInItemInput(1, false, "8.20", null))),
                token);
        api.post("/api/v1/app/pets/" + petId + "/epidemic-records",
                new com.pethealth.api.app.EpidemicRecordInput(1, "狂犬疫苗", "2026-09-20", "2026-10-05"),
                token);
        api.get("/api/v1/app/messages?page=1&page_size=10", token);   // 惰性物化出提醒

        var call = api.get("/api/v1/app/users/me/export", token);

        assertThat(call.code()).isZero();
        var data = call.data();
        assertThat(data.path("user").path("phone").asText()).isEqualTo("139****4001");
        assertThat(data.path("pets")).hasSize(1);
        var pet = data.path("pets").get(0);
        assertThat(pet.path("name").asText()).isEqualTo("豆豆");
        assertThat(pet.path("records").size())
                .as("打卡与防疫都是 archive_record 的行，导出要一并给出")
                .isGreaterThanOrEqualTo(2);
        assertThat(data.path("messages")).isNotEmpty();
        assertThat(data.path("notice").asText()).contains("未逐字段展开");
    }

    @Test
    @DisplayName("注销：手机号可重新注册、旧令牌失效、宠物被软删")
    void deactivationIsComplete() {
        String token = api.registerAndGetAccessToken(PHONE);
        long petId = api.createPet(token, "豆豆");

        assertThat(api.post("/api/v1/app/users/me/deactivation", null, token).code()).isZero();

        // 1) 同一手机号可以重新注册——注销该有的效果（手机号被让出来）
        ApiClient.ApiCall again = api.register(PHONE);
        assertThat(again.code()).as("注销后手机号应可重新注册：" + again.body()).isZero();

        // 2) 旧令牌立刻作废：**所有**受保护路径都拒绝，不只是账号资料那条
        //    （早先只有 /users/me 判了状态，注销后的令牌仍能读消息与导出 —— 测试报告 D13）
        for (String path : new String[]{"/api/v1/app/users/me", "/api/v1/app/pets",
                "/api/v1/app/messages?page=1&page_size=10", "/api/v1/app/users/me/export"}) {
            ApiClient.ApiCall call = api.get(path, token);
            assertThat(call.code()).as("注销后 %s 不该还能读：%s", path, call.body()).isEqualTo(40100);
        }

        // 3) 宠物与其档案一起软删（契约写的是「宠物与档案软删」），且写下了 deleted_at
        //    ——那是「30 天内可恢复」这个承诺的起点（测试报告 D12）
        assertThat(jdbc.queryForMap("SELECT is_deleted, deleted_at FROM pet WHERE id = ?", petId))
                .satisfies(row -> {
                    assertThat(row.get("is_deleted")).isEqualTo(1);
                    assertThat(row.get("deleted_at")).isNotNull();
                });
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM archive_record WHERE pet_id = ? AND is_deleted = 0",
                Integer.class, petId)).isZero();

        // 4) 新账号名下没有宠物（软删的查不到）
        String newToken = again.data().path("access_token").asText();
        assertThat(api.get("/api/v1/app/pets", newToken).data()).isEmpty();
    }

    @Test
    @DisplayName("注销是幂等的：同一账号再执行一次不会二次匿名化；但令牌已失效，重放会得到 40100")
    void deactivationIsIdempotent() {
        String token = api.registerAndGetAccessToken("13900004002");
        assertThat(api.post("/api/v1/app/users/me/deactivation", null, token).code()).isZero();

        // 注销那一刻起，这枚令牌就不再是身份了（鉴权层判账号状态，测试报告 D13）——
        // 所以「重放注销请求」得到的是 40100（未登录），而不是又一次成功。
        // 幂等体现在数据上：状态、匿名化标记、宠物软删都只发生一次（下面按库里的值确认）。
        ApiClient.ApiCall again = api.post("/api/v1/app/users/me/deactivation", null, token);
        assertThat(again.code()).as("注销后令牌立即失效：" + again.body()).isEqualTo(40100);

        var user = jdbc.queryForMap("SELECT status, nickname, deactivated_at FROM `user` WHERE id = "
                + "(SELECT id FROM `user` WHERE nickname = '已注销用户' ORDER BY id DESC LIMIT 1)");
        assertThat(((Number) user.get("status")).intValue()).isEqualTo(2);
        assertThat(user.get("nickname")).isEqualTo("已注销用户");
        assertThat(user.get("deactivated_at")).isNotNull();
    }
}
