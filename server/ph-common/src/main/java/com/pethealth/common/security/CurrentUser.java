package com.pethealth.common.security;

import com.pethealth.common.error.BusinessException;

/**
 * 当前请求的登录身份。
 *
 * <p>由各端的鉴权过滤器（C 端在 ph-account，服务者/运营后台后续各自实现）在校验通过后写入，
 * 请求结束时清掉。业务代码只读它，不去碰 HTTP 头。
 *
 * <p>三个端各自独立登录域（ADR-0012）：{@link LoginDomain} 必须与接口前缀匹配，
 * 所以这里同时记着身份和它来自哪个域。
 */
public final class CurrentUser {

    private static final ThreadLocal<Identity> HOLDER = new ThreadLocal<>();

    private CurrentUser() {
    }

    public record Identity(long userId, LoginDomain domain) {
    }

    public static void set(long userId, LoginDomain domain) {
        HOLDER.set(new Identity(userId, domain));
    }

    public static void clear() {
        HOLDER.remove();
    }

    public static boolean isLoggedIn() {
        return HOLDER.get() != null;
    }

    public static Identity get() {
        Identity identity = HOLDER.get();
        if (identity == null) {
            throw BusinessException.unauthorized("未登录");
        }
        return identity;
    }

    /** 当前登录用户 id——需要「谁在操作」的地方一律走这里（审计字段除外，那走 traceId 的 MDC）。 */
    public static long userId() {
        return get().userId();
    }

    /**
     * 校验当前身份必须属于指定登录域。C 端 Token 拿去调服务者后台接口会在这里被拦下。
     */
    public static long requireDomain(LoginDomain expected) {
        Identity identity = get();
        if (identity.domain() != expected) {
            throw BusinessException.unauthorized("当前登录身份不能访问该端的接口");
        }
        return identity.userId();
    }
}
