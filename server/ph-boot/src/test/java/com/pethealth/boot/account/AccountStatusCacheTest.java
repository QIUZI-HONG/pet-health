package com.pethealth.boot.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethealth.account.auth.AccountStatus;
import com.pethealth.boot.support.ApiClient;
import com.pethealth.boot.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 账号状态缓存（{@link AccountStatus}）的**时长契约**。
 *
 * <p>为什么值得单测：这张缓存把时长写错不会报任何错，只会静默地把人挡在门外——
 * 2026-10-01 实测踩到：本地库重建后，旧令牌让 `ph:auth:status:2` 被写成 30 天的 disabled
 * （当时「查无此人」与「禁用」共用同一个时长），之后新注册的账号（自增 id 被重新分配成 2）
 * 每个请求都 401「账号已注销或禁用」，而库里那条记录明明是正常状态。
 *
 * <p>所以把三种结论的缓存时长钉在这里：「可用」与「查无此人」都是一分钟级（自愈窗口），
 * **只有「禁用」才是长缓存**（注销是终态）。
 */
@DisplayName("账号状态缓存的时长契约（回归：查无此人不得按 30 天禁用缓存）")
class AccountStatusCacheTest extends IntegrationTestBase {

    private static final String KEY_PREFIX = "ph:auth:status:";
    /** 断言用的宽窗口：60s 的写法给一点余量，但离 30 天远得足以抓住本次缺陷 */
    private static final long SHORT_TTL_CEILING_SECONDS = Duration.ofMinutes(2).toSeconds();

    @Autowired
    private AccountStatus accountStatus;

    @Autowired
    private ObjectMapper objectMapper;

    private ApiClient api;

    @BeforeEach
    void setUpClient() {
        api = new ApiClient(rest, objectMapper);
    }

    @Test
    @DisplayName("查无此人：结论不可用，但缓存必须短——库重建/恢复会把 id 重新分配给新账号")
    void absentUserIsCachedBriefly() {
        long absent = 9_000_000_001L;

        assertThat(accountStatus.isActive(absent)).isFalse();
        assertThat(redis.getExpire(KEY_PREFIX + absent))
                .as("查无此人不能按 30 天缓存：id 会被重新分配，新账号一上来就被判「已注销」（2026-10-01 实测）")
                .isBetween(1L, SHORT_TTL_CEILING_SECONDS);
    }

    @Test
    @DisplayName("存在且启用：可用，短缓存（直接改库禁用也能在一分钟内生效的口径）")
    void activeUserIsCachedBriefly() {
        String token = api.registerAndGetAccessToken("13900006001");
        long userId = api.get("/api/v1/app/users/me", token).data().path("id").asLong();
        redis.delete(KEY_PREFIX + userId);

        assertThat(accountStatus.isActive(userId)).isTrue();
        assertThat(redis.getExpire(KEY_PREFIX + userId))
                .as("「可用」是短缓存：直接改库（绕开本代码）也要能在一分钟内生效")
                .isBetween(1L, SHORT_TTL_CEILING_SECONDS);
    }

    @Test
    @DisplayName("禁用：不可用，长缓存（注销是终态）")
    void disabledUserIsCachedLong() {
        String token = api.registerAndGetAccessToken("13900006002");
        long userId = api.get("/api/v1/app/users/me", token).data().path("id").asLong();
        jdbc.update("UPDATE `user` SET status = 2 WHERE id = ?", userId);
        // 清掉前面请求可能写下的短缓存，逼一次查库，验证「禁用」这一支写的是长缓存
        redis.delete(KEY_PREFIX + userId);

        assertThat(accountStatus.isActive(userId)).isFalse();
        assertThat(redis.getExpire(KEY_PREFIX + userId))
                .as("禁用按天缓存（30 天）——注销后不会再用回来")
                .isGreaterThan(Duration.ofDays(1).toSeconds());
    }
}
