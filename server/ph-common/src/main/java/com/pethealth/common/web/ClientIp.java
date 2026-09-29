package com.pethealth.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 取请求的来源 IP。限流与审计都要「谁在打」这个信息，规则收在这里一份。
 *
 * <p><b>只认 {@code getRemoteAddr()}，不读 {@code X-Forwarded-For}</b>（ADR-0028）：
 * 那个头是客户端可以随便写的。一旦信任它，限流就等于没有——攻击者每次改一个假 IP
 * 就是「新来源」，而审计里留下的也是他自己声明的地址，事后排查会指向无辜的第三方。
 *
 * <p>等部署方案（#66）定了反向代理，改这个类一处即可：加一个「可信代理」白名单开关，
 * 只有请求确实来自白名单内的代理时才采信转发头。**在此之前不要在任何别处读那个头。**
 */
public final class ClientIp {

    /** 拿不到请求上下文（定时任务、非 Web 线程）时返回空串，调用方按「未知来源」处理。 */
    public static final String UNKNOWN = "";

    private ClientIp() {
    }

    public static String of(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        return remote == null ? UNKNOWN : remote;
    }

    /**
     * 从当前请求上下文取来源 IP。
     *
     * <p>让 service 层不必把 {@code HttpServletRequest} 一路透传下来——审计要记 IP，
     * 但业务方法不该为了留痕多一个与业务无关的参数。
     */
    public static String current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return UNKNOWN;
        }
        Object request = attributes.resolveReference(RequestAttributes.REFERENCE_REQUEST);
        return request instanceof HttpServletRequest servletRequest ? of(servletRequest) : UNKNOWN;
    }
}
