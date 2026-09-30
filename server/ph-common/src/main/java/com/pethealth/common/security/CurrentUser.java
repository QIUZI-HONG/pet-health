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
     * 当前登录用户 id；未登录返回 {@code null}，**不抛异常**。
     *
     * <p>与 {@link #userId()} 的区别是语义而非风格：{@code userId()} 用在「这里必须有登录身份，
     * 没有就是 bug」的地方；这个用在「有没有都要继续」（限流按用户还是按 IP、
     * 审计记操作者还是记 0）的地方。混用会让「未登录」这件事在应该继续的路径上变成 40100。
     */
    public static Long idOrNull() {
        Identity identity = HOLDER.get();
        return identity == null ? null : identity.userId();
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

    /**
     * 运营后台的门禁：登录域必须是 {@link LoginDomain#ADMIN}，返回操作者 id（写审计要用）。
     *
     * <p>这是 {@link #requireDomain} 针对运营域的**具名写法**。原先有十个类各自定义了一个私有的
     * {@code requireAdmin()}（有的 {@code static long}、有的 {@code void}、注释各写一份），
     * 语义完全相同——收在这里之后「运营接口的门禁」只有一个定义。
     *
     * <p><b>它只校验登录域，不校验角色</b>：运营与超级管理员的细分待后台账号体系落地
     * （ADR-0035），在那之前所有 admin 域身份一视同仁，别把这里当成「是超级管理员」。
     */
    public static long requireAdmin() {
        return requireDomain(LoginDomain.ADMIN);
    }
}
