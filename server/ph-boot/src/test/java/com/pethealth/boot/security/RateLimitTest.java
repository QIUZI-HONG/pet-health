package com.pethealth.boot.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethealth.boot.support.ApiClient;
import com.pethealth.boot.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 接口限流（ADR-0028）。
 *
 * <p>单独一个测试类，因为要用**配置覆盖**把规则压到很小：默认阈值（认证接口 30/分钟、其余 600/分钟）
 * 是为了不误伤真实用户而定的，正常情况下永远打不满，也就永远测不到「打满之后会怎样」。
 * 这与 {@code FileSizeLimitTest} 压小体积上限是同一个手法——被验的路径与阈值数值无关。
 *
 * <p>这个类里 `app.rate-limit.rules` 被**整体替换**成一条针对注册接口的规则（2 次/分钟）：
 * 配置里给了索引 0，绑定器就不再采用构造函数里的默认列表。代价是多一个 Spring 上下文。
 *
 * <p>计数在 Redis 里，而基类每个用例前 `flushDb`，所以用例之间不会互相污染计数。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.rate-limit.enabled=true",
        "app.rate-limit.rules[0].path-prefix=/api/v1/app/auth/register",
        "app.rate-limit.rules[0].limit=2",
        "app.rate-limit.rules[0].window=60s",
})
@DisplayName("接口限流（ADR-0028）")
class RateLimitTest extends IntegrationTestBase {

    @Autowired
    private ObjectMapper objectMapper;

    private ApiClient api;

    @BeforeEach
    void setUpClient() {
        api = new ApiClient(rest, objectMapper);
    }

    /** 发一次原始 POST：用 URI 对象保证路径里的 %61 不被二次编码。 */
    private ResponseEntity<String> post(String url, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(URI.create(url), HttpMethod.POST,
                new HttpEntity<>(json, headers), String.class);
    }

    @Test
    @DisplayName("超过阈值返回 42900，且带 Retry-After")
    void exceedingTheLimitIsRejected() {
        assertThat(api.register("13900002001").code()).isZero();
        assertThat(api.register("13900002002").code()).isZero();

        // 第三次直接发原始请求：要读到响应头（Retry-After），而 ApiCall 只带信封
        ResponseEntity<String> third = post(rest.getRootUri() + "/api/v1/app/auth/register",
                "{\"phone\":\"13900002003\",\"password\":\"Passw0rd123\"}");

        assertThat(third.getStatusCode().value()).as("HTTP 状态码要是 429").isEqualTo(429);
        assertThat(third.getBody()).contains("42900").contains("频繁");
        // 契约（common.yaml 的 TooManyRequests）承诺带 Retry-After，前端据此做倒计时。
        // 断言响应头而不是只断言文案：文案会改，头是接口的一部分
        assertThat(third.getHeaders().getFirst("Retry-After"))
                .as("必须带 Retry-After 供前端倒计时").isNotNull();
        // 限流发生在业务之前：被挡的这次不该建出账号
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `user`", Integer.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("百分号编码的路径绕不过限流（限流与路由必须用同一个路径口径）")
    void percentEncodedPathIsStillLimited() {
        // 这条是回归用例：容器给的 getRequestURI() 是**未解码**的，而 Tomcat 按解码后的路径路由。
        // 早先按原始 URI 做前缀匹配，于是 /%61pi/v1/app/auth/register（%61 = a）能进到注册控制器、
        // 却匹配不上 /api/v1/app/auth/ 这条规则 —— 等于把限流整个绕开（实测过）。
        // 现在两侧都走 RequestPaths（Spring 自己的归一化），所以编不编码命中同一条规则、同一个计数器。
        assertThat(api.register("13900002011").code()).isZero();
        assertThat(api.register("13900002012").code()).isZero();

        // 用 URI 对象发，避免把 %61 二次编码成 %2561（那样测的就不是这条路径了）
        ResponseEntity<String> encoded = post(rest.getRootUri() + "/%61pi/v1/app/auth/register",
                "{\"phone\":\"13900002013\",\"password\":\"Passw0rd123\"}");

        assertThat(encoded.getStatusCode().value())
                .as("编码路径必须与正常路径共享同一个计数器，因此第 3 次照样被拦")
                .isEqualTo(429);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `user`", Integer.class))
                .as("被拦下的那次不该建出账号").isEqualTo(2);
    }

    @Test
    @DisplayName("没被规则命中的接口不受影响（限流不能误伤）")
    void unmatchedPathIsNotLimited() {
        // 规则只覆盖注册接口，其余的路径不匹配任何规则 → 不限流。
        // 用「未登录读自己的资料」这条路径：它必然返回 40100，不会与 LoginThrottle 的 42900 混淆
        // （那个限流是按手机号计数的，用登录接口来测会让这条断言分不清是谁拦的）。
        for (int i = 0; i < 5; i++) {
            assertThat(api.get("/api/v1/app/users/me", null).code())
                    .as("没有命中规则就不该被限流").isEqualTo(40100);
        }
    }
}
