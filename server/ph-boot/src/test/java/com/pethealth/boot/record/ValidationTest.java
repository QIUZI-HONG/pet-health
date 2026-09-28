package com.pethealth.boot.record;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethealth.api.app.PetCreateRequest;
import com.pethealth.api.app.RegisterRequest;
import com.pethealth.boot.support.ApiClient;
import com.pethealth.common.time.AppTime;
import com.pethealth.boot.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 参数校验：错误码 40001 + 能看出是哪个字段错了。
 *
 * <p>交付文档 8.2 对 40001 的要求是「提示具体字段」，所以这里不只断错误码，也断提示里带字段名。
 */
class ValidationTest extends IntegrationTestBase {

    private ApiClient api;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUpClient() {
        api = new ApiClient(rest, objectMapper);
    }

    @Test
    @DisplayName("注册：手机号格式、密码强度都要拦")
    void registerValidation() {
        assertParamInvalid(api.post("/api/v1/app/auth/register", new RegisterRequest("12345", ApiClient.DEFAULT_PASSWORD, null)),
                "手机号");
        assertParamInvalid(api.post("/api/v1/app/auth/register", new RegisterRequest("13800138000", "short", null)),
                "密码");
        assertParamInvalid(api.post("/api/v1/app/auth/register", new RegisterRequest("13800138000", "onlyletters", null)),
                "密码");
        assertParamInvalid(api.post("/api/v1/app/auth/register", new RegisterRequest("13800138000", "12345678", null)),
                "密码");
    }

    @Test
    @DisplayName("建档：必填、物种范围、体重格式、生日不能是将来")
    void petValidation() {
        String token = api.registerAndGetAccessToken("13800138101");

        assertParamInvalid(api.post("/api/v1/app/pets",
                new PetCreateRequest("   ", 1, null, 0, null, null, null, null, null, null), token), "昵称");
        assertParamInvalid(api.post("/api/v1/app/pets",
                new PetCreateRequest("豆豆", 3, null, 0, null, null, null, null, null, null), token), "物种");
        assertParamInvalid(api.post("/api/v1/app/pets",
                new PetCreateRequest("豆豆", 1, null, 0, null, "abc", null, null, null, null), token), "体重");
        assertParamInvalid(api.post("/api/v1/app/pets",
                new PetCreateRequest("豆豆", 1, null, 0, AppTime.today().plusDays(1), null, null, null, null, null),
                token), "生日");
    }

    @Test
    @DisplayName("慢病标记为 true 时必须写描述")
    void chronicRequiresDescription() {
        String token = api.registerAndGetAccessToken("13800138102");

        ApiClient.ApiCall call = api.post("/api/v1/app/pets",
                new PetCreateRequest("豆豆", 1, null, 0, null, null, null, null, true, null), token);

        assertThat(call.status()).isEqualTo(400);
        assertThat(call.code()).isEqualTo(40001);
        assertThat(call.message()).contains("慢病描述");
    }

    @Test
    @DisplayName("编辑时把慢病标记去掉，慢病描述要一起清掉")
    void clearingChronicFlagClearsDescription() {
        String token = api.registerAndGetAccessToken("13800138103");
        long petId = api.post("/api/v1/app/pets",
                        new PetCreateRequest("豆豆", 1, null, 0, null, null, null, null, true, "关节炎"), token)
                .data().path("id").asLong();

        ApiClient.ApiCall call = api.put("/api/v1/app/pets/" + petId,
                Map.of("is_chronic", false), token);

        assertThat(call.code()).isZero();
        assertThat(call.data().path("is_chronic").asBoolean()).isFalse();
        assertThat(call.data().path("chronic_desc").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT chronic_desc FROM pet WHERE id = ?", String.class, petId)).isNull();
    }

    @Test
    @DisplayName("请求体不是合法 JSON 或字段类型不对，返回 40001 而不是 50000")
    void malformedBodyIsParamError() {
        String token = api.registerAndGetAccessToken("13800138104");

        Map<String, Object> wrongType = new HashMap<>();
        wrongType.put("name", "豆豆");
        wrongType.put("species", "狗");           // 应该是数字
        ApiClient.ApiCall call = api.postRaw("/api/v1/app/pets", wrongType, token);

        assertThat(call.status()).isEqualTo(400);
        assertThat(call.code()).isEqualTo(40001);
    }

    @Test
    @DisplayName("不存在的路径返回 40400，不是 50000")
    void unknownPathIsNotFound() {
        // 带上 Token：/api/v1/app/** 下的未登录请求会先被鉴权拦成 40100（这是对的，
        // 不该向匿名调用方透露「这个接口存不存在」），所以要看 40400 得先过鉴权
        String token = api.registerAndGetAccessToken("13800138105");

        ApiClient.ApiCall call = api.get("/api/v1/app/no-such-endpoint", token);

        assertThat(call.status()).isEqualTo(404);
        assertThat(call.code()).isEqualTo(40400);
    }

    @Test
    @DisplayName("未登录访问 /api/v1/app/** 下的任何路径都是 40100")
    void anonymousRequestIsUnauthorized() {
        ApiClient.ApiCall call = api.get("/api/v1/app/no-such-endpoint", null);

        assertThat(call.status()).isEqualTo(401);
        assertThat(call.code()).isEqualTo(40100);
    }

    /**
     * 断言「参数错误」：状态 400 / 业务码 40001 / **文案里能看出是哪个字段**。
     *
     * <p>这里断言的是中文片段而不是英文字段键：`message` 是「前端直接展示」的文案，
     * 早先会拼成「weight 体重需小于 1000」这种把内部标识甩给用户的样子（测试报告 D25），
     * 现在字段名只进日志、用户看到的是完整的中文句子。
     */
    private void assertParamInvalid(ApiClient.ApiCall call, String messageFragment) {
        assertThat(call.status()).as(call.body().toPrettyString()).isEqualTo(400);
        assertThat(call.code()).isEqualTo(40001);
        assertThat(call.message()).as(call.body().toPrettyString()).contains(messageFragment);
    }

}
