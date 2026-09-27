package com.pethealth.boot.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/**
 * 测试用的 HTTP 客户端：把「统一响应」拆成好断言的形状。
 *
 * <p>刻意不封装成强类型客户端——接口测试要顺手能断言 status、code、message 与原始 JSON 字段名，
 * 强类型反而会把「响应字段名写错了」这类问题挡在断言之外，而那正是契约对齐要盯的东西。
 */
public final class ApiClient {

    /** 测试统一口令，满足注册的强度要求（字母 + 数字、8–32 位）。 */
    public static final String DEFAULT_PASSWORD = "pet12345";

    private final TestRestTemplate rest;
    private final ObjectMapper objectMapper;

    public ApiClient(TestRestTemplate rest, ObjectMapper objectMapper) {
        this.rest = rest;
        this.objectMapper = objectMapper;
    }

    /** 一次调用的结果：HTTP 状态 + 响应体。 */
    public record ApiCall(int status, JsonNode body) {

        public int code() {
            return body.path("code").asInt(-1);
        }

        public String message() {
            return body.path("message").asText();
        }

        public JsonNode data() {
            return body.path("data");
        }

        public String requestId() {
            return body.path("request_id").asText();
        }
    }

    public ApiCall post(String path, Object body) {
        return post(path, body, null);
    }

    /** 注册一个账号（口令用测试统一值）。手机号各用例自己挑，避免撞号。 */
    public ApiCall register(String phone) {
        return post("/api/v1/app/auth/register",
                new com.pethealth.api.app.RegisterRequest(phone, DEFAULT_PASSWORD, null));
    }

    public String registerAndGetAccessToken(String phone) {
        return register(phone).data().path("access_token").asText();
    }

    public String registerAndGetRefreshToken(String phone) {
        return register(phone).data().path("refresh_token").asText();
    }

    /** 建一只有指定生日与慢病标记的宠物（评分测试要用年龄/慢病触发老年专项）。 */
    public long createPetDetailed(String accessToken, String name, java.time.LocalDate birthday, boolean chronic) {
        ApiCall call = post("/api/v1/app/pets",
                new com.pethealth.api.app.PetCreateRequest(name, 1, "柯基", 0, birthday, null, null,
                        null, chronic, chronic ? "慢病照护" : null),
                accessToken);
        if (call.code() != 0) {
            throw new AssertionError("建档应当成功，实际：" + call.body());
        }
        return call.data().path("id").asLong();
    }

    /** 建一只最小的宠物（只有昵称与物种），返回 id。 */
    public long createPet(String accessToken, String name) {
        ApiCall call = post("/api/v1/app/pets",
                new com.pethealth.api.app.PetCreateRequest(name, 1, null, 0, null, null, null, null, null, null),
                accessToken);
        if (call.code() != 0) {
            throw new AssertionError("建档应当成功，实际：" + call.body());
        }
        return call.data().path("id").asLong();
    }

    public ApiCall post(String path, Object body, String accessToken) {
        return exchange(HttpMethod.POST, path, body, accessToken);
    }

    public ApiCall get(String path, String accessToken) {
        return exchange(HttpMethod.GET, path, null, accessToken);
    }

    public ApiCall put(String path, Object body, String accessToken) {
        return exchange(HttpMethod.PUT, path, body, accessToken);
    }

    public ApiCall delete(String path, String accessToken) {
        return exchange(HttpMethod.DELETE, path, null, accessToken);
    }

    /** 发一个「字段名不会被 Jackson 改名」的原始 JSON，用来验证契约字段名。 */
    public ApiCall postRaw(String path, Map<String, Object> rawBody, String accessToken) {
        return exchange(HttpMethod.POST, path, rawBody, accessToken);
    }

    private ApiCall exchange(HttpMethod method, String path, Object body, String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (accessToken != null) {
            headers.setBearerAuth(accessToken);
        }
        ResponseEntity<String> response = rest.exchange(path, method,
                new HttpEntity<>(body, headers), String.class);
        try {
            JsonNode json = objectMapper.readTree(response.getBody() == null ? "{}" : response.getBody());
            return new ApiCall(response.getStatusCode().value(), json);
        } catch (Exception e) {
            throw new IllegalStateException("响应不是合法 JSON：" + response.getBody(), e);
        }
    }
}
