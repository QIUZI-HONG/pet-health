package com.pethealth.account.auth;

import com.pethealth.account.config.AuthProperties;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.error.ErrorCode;
import com.pethealth.common.security.LoginDomain;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Access Token 的签发与校验（ADR-0012）：JWT / HS256 / 2 小时，无状态校验。
 *
 * <p>只有两处会失败：过期（40101，前端拿 Refresh 换新的）和无效（40100，跳登录）。
 * 两者必须区分开——前端行为完全不同。
 */
@Service
public class JwtService {

    private static final String CLAIM_DOMAIN = "domain";
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final Duration accessTtl;

    public JwtService(AuthProperties properties) {
        byte[] secret = properties.jwtSecret() == null
                ? new byte[0]
                : properties.jwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < MIN_SECRET_BYTES) {
            // HS256 的密钥短于 32 字节时 jjwt 会直接拒绝签名；在这里提前给出可读的错
            throw new IllegalStateException("app.auth.jwt-secret 至少需要 " + MIN_SECRET_BYTES
                    + " 字节（当前 " + secret.length + " 字节）：请在 server/.env 或环境变量 JWT_SECRET 里配一个随机长串，"
                    + "生成方式见 server/.env.example");
        }
        this.key = Keys.hmacShaKeyFor(secret);
        this.accessTtl = properties.accessTtl();
    }

    public record AccessToken(long userId, LoginDomain domain, String jwtId) {
    }

    /** 签发结果：Token 本身 + 剩余有效秒数（返回给前端的是后者）。 */
    public record Issued(String token, long expiresInSeconds) {
    }

    public Issued issue(long userId, LoginDomain domain) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(accessTtl);
        String token = Jwts.builder()
                .subject(Long.toString(userId))
                .claim(CLAIM_DOMAIN, domain.name())
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new Issued(token, accessTtl.toSeconds());
    }

    /** 校验并解出载荷。过期抛 40101，其它一律 40100。 */
    public AccessToken parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return new AccessToken(
                    Long.parseLong(claims.getSubject()),
                    LoginDomain.valueOf(claims.get(CLAIM_DOMAIN, String.class)),
                    claims.getId());
        } catch (ExpiredJwtException e) {
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException e) {
            // 签名不对、格式不对、域信息缺失——都按「未登录」处理，不向外泄露具体原因
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Token 无效");
        }
    }
}
