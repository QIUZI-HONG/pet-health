package com.pethealth.api.app;

/**
 * 令牌对，对应 contract/app.yaml 的 {@code TokenPair}。
 *
 * <p>{@code refreshToken} 一次性使用：换发新令牌时旧的立即作废（ADR-0012）。
 */
public record TokenPair(
        String accessToken,
        String refreshToken,
        int expiresIn,
        UserProfile user) {
}
