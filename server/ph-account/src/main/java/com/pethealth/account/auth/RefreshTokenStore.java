package com.pethealth.account.auth;

import com.pethealth.account.config.AuthProperties;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.error.ErrorCode;
import com.pethealth.common.security.LoginDomain;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Refresh Token 的存放与轮换（ADR-0012）。
 *
 * <p>Refresh Token 是**不透明随机串**，本身不含任何信息；Redis 里存的是它的 SHA-256 摘要
 * ——万一 Redis 被读到，拿到的也只是摘要，不能直接拿去换令牌。
 *
 * <p>一次性使用：{@link #consume} 用 {@code GETDEL} 原子取走，取到即作废，然后签发新的一对。
 * 同一个 Refresh 第二次出现必然是异常（泄露或重放），按吊销处理。
 */
@Service
public class RefreshTokenStore {

    private static final String KEY_PREFIX = "ph:auth:refresh:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final Duration refreshTtl;

    public RefreshTokenStore(StringRedisTemplate redis, AuthProperties properties) {
        this.redis = redis;
        this.refreshTtl = properties.refreshTtl();
    }

    /** 会话内容：谁、哪个登录域。 */
    public record Session(long userId, LoginDomain domain) {
    }

    public String issue(long userId, LoginDomain domain) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        redis.opsForValue().set(key(token), serialize(new Session(userId, domain)), refreshTtl);
        return token;
    }

    /** 取走并作废。token 不存在（已用过、已登出、已过期）一律 40101。 */
    public Session consume(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED, "refresh_token 不能为空");
        }
        String value = redis.opsForValue().getAndDelete(key(token));
        if (value == null) {
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED, "Refresh Token 已失效，请重新登录");
        }
        return parse(value);
    }

    /** 退出登录：删掉即可，重复调用不报错（幂等）。 */
    public void revoke(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        redis.delete(key(token));
    }

    public Duration ttl() {
        return refreshTtl;
    }

    private String key(String token) {
        return KEY_PREFIX + sha256(token);
    }

    private String serialize(Session session) {
        return session.userId() + ":" + session.domain().name();
    }

    private Session parse(String value) {
        int separator = value.indexOf(':');
        if (separator <= 0) {
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED, "Refresh Token 数据已损坏，请重新登录");
        }
        try {
            return new Session(
                    Long.parseLong(value.substring(0, separator)),
                    LoginDomain.valueOf(value.substring(separator + 1)));
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED, "Refresh Token 数据已损坏，请重新登录");
        }
    }

    private String sha256(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}
