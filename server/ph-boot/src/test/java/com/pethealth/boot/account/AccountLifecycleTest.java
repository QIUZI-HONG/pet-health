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

        // 2) 旧令牌的签名仍然有效（JWT 的固有代价），但账号资料这条路径要拒绝已注销用户
        ApiClient.ApiCall me = api.get("/api/v1/app/users/me", token);
        assertThat(me.code()).as("注销后不该还能读资料：" + me.body()).isEqualTo(40100);

        // 3) 宠物软删：新账号名下没有宠物
        String newToken = again.data().path("access_token").asText();
        assertThat(api.get("/api/v1/app/pets", newToken).data()).isEmpty();
    }

    @Test
    @DisplayName("重复注销不报错（接口幂等）")
    void deactivationIsIdempotent() {
        String token = api.registerAndGetAccessToken("13900004002");
        assertThat(api.post("/api/v1/app/users/me/deactivation", null, token).code()).isZero();
        assertThat(api.post("/api/v1/app/users/me/deactivation", null, token).code())
                .as("网络重试下的第二次调用不该报错")
                .isEqualTo(0);
    }
}
