package com.pethealth.common.web;

import com.pethealth.common.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 「这次请求是谁发的」——限流与幂等都要这个标识，规则收在这里一份（ADR-0028）。
 *
 * <p>已登录用用户 id，未登录退回来源 IP。两种情形都带前缀（{@code u:} / {@code ip:}），
 * 免得「用户 42」与「IP 里的 42」撞成同一个键。
 *
 * <p>**为什么不用 IP 优先**：同一个出口后面的多个用户会共享配额，且 NAT 场景误伤面大；
 * IP 只是「认不出人」时的退路。
 */
public final class CallerKey {

    private CallerKey() {
    }

    public static String of(HttpServletRequest request) {
        Long userId = CurrentUser.idOrNull();
        return userId != null ? "u:" + userId : "ip:" + ClientIp.of(request);
    }
}
