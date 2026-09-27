package com.pethealth.account.auth;

import com.pethealth.account.config.AuthProperties;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.error.ErrorCode;
import com.pethealth.common.security.LoginDomain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/**
 * Refresh Token 的存放、轮换与泄露检测（ADR-0012）。
 *
 * <p>Refresh Token 是**不透明随机串**，本身不含信息；Redis 里存的是它的 SHA-256 摘要——
 * 万一 Redis 被读到，拿到的也只是摘要，不能直接拿去换令牌。
 *
 * <p>三个键各管一件事：
 *
 * <ul>
 *   <li>{@code ph:auth:refresh:<hash>} → 还没用过的令牌（{@code GETDEL} 取走即失效，保证一次性）；
 *   <li>{@code ph:auth:refresh-used:<hash>} → 已经用过的令牌，**它存在的唯一目的是发现重放**：
 *       同一个令牌第二次出现，说明它被复制走了（正常客户端换完就把旧的丢了）；
 *   <li>{@code ph:auth:refresh-family:<familyId>} → 同一次登录（族）里签发过的全部令牌，
 *       用于重放时整族吊销。
 * </ul>
 */
@Service
public class RefreshTokenStore {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenStore.class);

    private static final String KEY_PREFIX = "ph:auth:refresh:";
    private static final String USED_PREFIX = "ph:auth:refresh-used:";
    private static final String FAMILY_PREFIX = "ph:auth:refresh-family:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final Duration refreshTtl;

    public RefreshTokenStore(StringRedisTemplate redis, AuthProperties properties) {
        this.redis = redis;
        this.refreshTtl = properties.refreshTtl();
    }

    /** 一次会话：谁、哪个登录域、以及这一族的编号。 */
    public record Session(long userId, LoginDomain domain, String familyId) {
    }

    /** 新登录 / 注册：开一个新族。 */
    public String issue(long userId, LoginDomain domain) {
        return issueInFamily(UUID.randomUUID().toString(), userId, domain);
    }

    /** 在既有族里签发下一个令牌（轮换用）。 */
    public String issueInFamily(String familyId, long userId, LoginDomain domain) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String hash = sha256(token);

        redis.opsForValue().set(KEY_PREFIX + hash, serialize(new Session(userId, domain, familyId)), refreshTtl);
        String familyKey = FAMILY_PREFIX + familyId;
        redis.opsForSet().add(familyKey, hash);
        redis.expire(familyKey, refreshTtl);
        return token;
    }

    /**
     * 取走并作废（一次性）。取不到时分两种情况：**从未签发**（普通失效），或**已经用过**——
     * 后者是重放信号，按 ADR-0012 处理：整族吊销，该用户重新登录。
     */
    public Session consume(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED, "refresh_token 不能为空");
        }
        String hash = sha256(token);
        String value = redis.opsForValue().getAndDelete(KEY_PREFIX + hash);
        if (value == null) {
            String replayedFamily = redis.opsForValue().get(USED_PREFIX + hash);
            if (replayedFamily != null) {
                revokeFamily(replayedFamily);
                log.warn("检测到 Refresh Token 重复使用，已吊销整个会话族 familyId={}", replayedFamily);
                throw new BusinessException(ErrorCode.TOKEN_EXPIRED,
                        "检测到登录凭证被重复使用，已退出该会话，请重新登录");
            }
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED, "Refresh Token 已失效，请重新登录");
        }
        Session session = parse(value);
        // 留个「用过」的标记：下一个拿它来换的人就能被发现
        redis.opsForValue().set(USED_PREFIX + hash, session.familyId(), refreshTtl);
        return session;
    }

    /** 退出登录：作废当前这一个令牌，重复调用不报错（幂等）。 */
    public void revoke(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        revokeToken(sha256(token));
    }

    /** 整族吊销：泄露检测触发时用，把这次登录签发过的所有令牌一次清掉。 */
    public void revokeFamily(String familyId) {
        Set<String> hashes = redis.opsForSet().members(FAMILY_PREFIX + familyId);
        if (hashes != null) {
            for (String hash : hashes) {
                revokeToken(hash);
            }
        }
        redis.delete(FAMILY_PREFIX + familyId);
    }

    private void revokeToken(String hash) {
        redis.delete(KEY_PREFIX + hash);
        redis.delete(USED_PREFIX + hash);
    }

    private String serialize(Session session) {
        return session.familyId() + ":" + session.userId() + ":" + session.domain().name();
    }

    private Session parse(String value) {
        String[] parts = value.split(":");
        if (parts.length != 3) {
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED, "Refresh Token 数据已损坏，请重新登录");
        }
        try {
            return new Session(Long.parseLong(parts[1]), LoginDomain.valueOf(parts[2]), parts[0]);
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
