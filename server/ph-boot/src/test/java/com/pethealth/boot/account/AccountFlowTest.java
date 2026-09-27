package com.pethealth.boot.account;

import com.pethealth.api.app.LoginRequest;
import com.pethealth.api.app.RegisterRequest;
import com.pethealth.boot.support.ApiClient;
import com.pethealth.boot.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 账号切片：注册 / 登录 / 刷新 / 退出 / 资料（切片 #94 的验收标准）。
 *
 * <p>全部打在真实 HTTP + 真实 MySQL + 真实 Redis 上，不 mock（ADR-0014）。
 */
class AccountFlowTest extends IntegrationTestBase {

    private static final String PHONE = "13800138000";
    private static final String PASSWORD = ApiClient.DEFAULT_PASSWORD;

    private ApiClient api;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUpClient() {
        api = new ApiClient(rest, objectMapper);
    }

    @Test
    @DisplayName("注册成功即返回令牌与脱敏手机号")
    void registerReturnsTokensAndMaskedPhone() {
        ApiClient.ApiCall call = api.post("/api/v1/app/auth/register",
                new RegisterRequest(PHONE, PASSWORD, "小张"));

        assertThat(call.status()).isEqualTo(200);
        assertThat(call.code()).isZero();
        assertThat(call.data().path("access_token").asText()).isNotBlank();
        assertThat(call.data().path("refresh_token").asText()).isNotBlank();
        assertThat(call.data().path("expires_in").asInt()).isEqualTo(7200);
        assertThat(call.data().path("user").path("phone").asText()).isEqualTo("138****8000");
        assertThat(call.data().path("user").path("nickname").asText()).isEqualTo("小张");
        assertThat(call.requestId()).isNotBlank();
    }

    @Test
    @DisplayName("手机号在库里是密文 + HMAC，明文不落库")
    void phoneIsStoredEncrypted() {
        api.post("/api/v1/app/auth/register", new RegisterRequest(PHONE, PASSWORD, null));

        String enc = jdbc.queryForObject("SELECT phone_enc FROM `user` WHERE is_deleted = 0", String.class);
        String hash = jdbc.queryForObject("SELECT phone_hash FROM `user` WHERE is_deleted = 0", String.class);

        assertThat(enc).startsWith("v1:").doesNotContain(PHONE).doesNotContain("138");
        assertThat(hash).hasSize(64).doesNotContain(PHONE);
        // 口令同样不能落明文
        String passwordHash = jdbc.queryForObject("SELECT password_hash FROM `user` WHERE is_deleted = 0",
                String.class);
        assertThat(passwordHash).startsWith("$2a$").doesNotContain(PASSWORD);
    }

    @Test
    @DisplayName("同一个手机号重复注册返回 40900")
    void duplicatePhoneIsRejected() {
        api.post("/api/v1/app/auth/register", new RegisterRequest(PHONE, PASSWORD, null));

        ApiClient.ApiCall second = api.post("/api/v1/app/auth/register",
                new RegisterRequest(PHONE, PASSWORD, null));

        assertThat(second.status()).isEqualTo(409);
        assertThat(second.code()).isEqualTo(40900);
    }

    @Test
    @DisplayName("登录成功；密码错误返回 40100 且不说具体原因")
    void loginWithoutCorrectPassword() {
        api.post("/api/v1/app/auth/register", new RegisterRequest(PHONE, PASSWORD, null));

        ApiClient.ApiCall ok = api.post("/api/v1/app/auth/login", new LoginRequest(PHONE, PASSWORD));
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.data().path("access_token").asText()).isNotBlank();

        ApiClient.ApiCall wrongPassword = api.post("/api/v1/app/auth/login",
                new LoginRequest(PHONE, "wrong12345"));
        assertThat(wrongPassword.status()).isEqualTo(401);
        assertThat(wrongPassword.code()).isEqualTo(40100);
        assertThat(wrongPassword.message()).isEqualTo("手机号或密码不正确");

        // 不存在的手机号给同一句话，避免接口变成「这个号注册过没有」的探测工具
        ApiClient.ApiCall unknownPhone = api.post("/api/v1/app/auth/login",
                new LoginRequest("13900139000", PASSWORD));
        assertThat(unknownPhone.status()).isEqualTo(401);
        assertThat(unknownPhone.message()).isEqualTo(wrongPassword.message());
    }

    @Test
    @DisplayName("连续失败超过阈值后返回 42900")
    void loginIsThrottledAfterRepeatedFailures() {
        api.post("/api/v1/app/auth/register", new RegisterRequest(PHONE, PASSWORD, null));

        ApiClient.ApiCall last = null;
        for (int i = 0; i < 11; i++) {
            last = api.post("/api/v1/app/auth/login", new LoginRequest(PHONE, "wrong12345"));
        }

        assertThat(last).isNotNull();
        assertThat(last.status()).isEqualTo(429);
        assertThat(last.code()).isEqualTo(42900);
    }

    @Test
    @DisplayName("Refresh 一次性使用：换发后旧的立即失效")
    void refreshTokenRotates() {
        String refreshToken = api.post("/api/v1/app/auth/register",
                new RegisterRequest(PHONE, PASSWORD, null)).data().path("refresh_token").asText();

        ApiClient.ApiCall first = api.post("/api/v1/app/auth/refresh", java.util.Map.of("refresh_token", refreshToken));
        assertThat(first.code()).isZero();
        String newRefresh = first.data().path("refresh_token").asText();
        assertThat(newRefresh).isNotEqualTo(refreshToken);

        // 新的可以用（正常轮换路径）
        assertThat(api.post("/api/v1/app/auth/refresh", java.util.Map.of("refresh_token", newRefresh)).code()).isZero();
    }

    @Test
    @DisplayName("Refresh 被重复使用＝泄露信号：整个会话族一起吊销（ADR-0012）")
    void replayedRefreshTokenRevokesWholeSession() {
        String refreshToken = api.post("/api/v1/app/auth/register",
                new RegisterRequest(PHONE, PASSWORD, null)).data().path("refresh_token").asText();

        // 正常换发一次，拿到同一会话族里的新令牌
        ApiClient.ApiCall rotated = api.post("/api/v1/app/auth/refresh", java.util.Map.of("refresh_token", refreshToken));
        String newRefresh = rotated.data().path("refresh_token").asText();

        // 攻击者拿着已经用过的旧令牌再来一次 → 40101
        ApiClient.ApiCall replay = api.post("/api/v1/app/auth/refresh", java.util.Map.of("refresh_token", refreshToken));
        assertThat(replay.status()).isEqualTo(401);
        assertThat(replay.code()).isEqualTo(40101);

        // 关键：合法用户手里那个新令牌也一并失效——因为无法分辨谁是攻击者，
        // 只能让这次登录整个作废，让用户重新登录（这正是 ADR-0012 说的「按吊销处理」）
        ApiClient.ApiCall afterReplay = api.post("/api/v1/app/auth/refresh", java.util.Map.of("refresh_token", newRefresh));
        assertThat(afterReplay.code()).isEqualTo(40101);

        // 重新登录还能拿到可用的令牌
        String freshRefresh = api.post("/api/v1/app/auth/login", new LoginRequest(PHONE, PASSWORD))
                .data().path("refresh_token").asText();
        assertThat(api.post("/api/v1/app/auth/refresh", java.util.Map.of("refresh_token", freshRefresh)).code()).isZero();
    }

    @Test
    @DisplayName("退出登录后 Refresh 失效")
    void logoutRevokesRefreshToken() {
        String accessToken = api.post("/api/v1/app/auth/register",
                new RegisterRequest(PHONE, PASSWORD, null)).data().path("access_token").asText();
        String refreshToken = api.post("/api/v1/app/auth/login", new LoginRequest(PHONE, PASSWORD))
                .data().path("refresh_token").asText();

        ApiClient.ApiCall logout = api.post("/api/v1/app/auth/logout",
                java.util.Map.of("refresh_token", refreshToken), accessToken);
        assertThat(logout.code()).isZero();

        ApiClient.ApiCall after = api.post("/api/v1/app/auth/refresh", java.util.Map.of("refresh_token", refreshToken));
        assertThat(after.code()).isEqualTo(40101);
    }

    @Test
    @DisplayName("Access Token 过期后：40101 → 用 Refresh 换新的 → 新令牌能正常访问")
    void expiredAccessTokenIsRecoveredByRefresh(@Autowired com.pethealth.account.config.AuthProperties props) {
        ApiClient.ApiCall registered = api.register(PHONE);
        String refreshToken = registered.data().path("refresh_token").asText();
        long userId = registered.data().path("user").path("id").asLong();

        // 用一个「有效期 -1 秒」的服务签一个必然过期的 Token，模拟「用户放了一会儿再回来」
        com.pethealth.account.auth.JwtService expiredIssuer = new com.pethealth.account.auth.JwtService(
                new com.pethealth.account.config.AuthProperties(props.jwtSecret(), java.time.Duration.ofSeconds(-1),
                        props.refreshTtl(), props.loginMaxFailures(), props.loginLockWindow()));
        String expiredAccess = expiredIssuer.issue(userId, com.pethealth.common.security.LoginDomain.APP).token();

        ApiClient.ApiCall expired = api.get("/api/v1/app/users/me", expiredAccess);
        assertThat(expired.code()).isEqualTo(40101);

        // 前端看到 40101 就静默刷新——这才是「会话过期能刷新」那条验收标准的实际路径
        ApiClient.ApiCall refreshed = api.post("/api/v1/app/auth/refresh",
                java.util.Map.of("refresh_token", refreshToken));
        assertThat(refreshed.code()).isZero();

        ApiClient.ApiCall me = api.get("/api/v1/app/users/me", refreshed.data().path("access_token").asText());
        assertThat(me.code()).isZero();
        assertThat(me.data().path("phone").asText()).isEqualTo("138****8000");
    }

    @Test
    @DisplayName("未带 Token 访问受保护接口返回 40100")
    void protectedEndpointRequiresToken() {
        ApiClient.ApiCall call = api.get("/api/v1/app/users/me", null);

        assertThat(call.status()).isEqualTo(401);
        assertThat(call.code()).isEqualTo(40100);
        assertThat(call.requestId()).isNotBlank();
    }

    @Test
    @DisplayName("Token 无效返回 40100；Token 过期返回 40101")
    void invalidAndExpiredTokenAreDistinguished(@Autowired com.pethealth.account.config.AuthProperties props) {
        ApiClient.ApiCall garbage = api.get("/api/v1/app/users/me", "not-a-jwt");
        assertThat(garbage.code()).isEqualTo(40100);

        // 用一个「有效期 -1 秒」的服务签一个必然过期的 Token，验证 40101 这条分支
        com.pethealth.account.auth.JwtService expiredIssuer = new com.pethealth.account.auth.JwtService(
                new com.pethealth.account.config.AuthProperties(props.jwtSecret(), java.time.Duration.ofSeconds(-1),
                        props.refreshTtl(), props.loginMaxFailures(), props.loginLockWindow()));
        String expired = expiredIssuer.issue(1L, com.pethealth.common.security.LoginDomain.APP).token();

        ApiClient.ApiCall call = api.get("/api/v1/app/users/me", expired);
        assertThat(call.status()).isEqualTo(401);
        assertThat(call.code()).isEqualTo(40101);
    }

    @Test
    @DisplayName("资料可以更新，昵称与性别落到库里")
    void updateProfile() {
        String accessToken = api.post("/api/v1/app/auth/register",
                new RegisterRequest(PHONE, PASSWORD, null)).data().path("access_token").asText();

        ApiClient.ApiCall call = api.put("/api/v1/app/users/me",
                java.util.Map.of("nickname", "铲屎官", "gender", 2), accessToken);

        assertThat(call.code()).isZero();
        assertThat(call.data().path("nickname").asText()).isEqualTo("铲屎官");
        assertThat(call.data().path("gender").asInt()).isEqualTo(2);

        ApiClient.ApiCall me = api.get("/api/v1/app/users/me", accessToken);
        assertThat(me.data().path("nickname").asText()).isEqualTo("铲屎官");
    }
}
