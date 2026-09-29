package com.pethealth.account.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethealth.account.auth.RateLimiter;
import com.pethealth.account.config.RateLimitProperties;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.error.ErrorCode;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.web.ClientIp;
import com.pethealth.common.web.RequestPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * 接口限流（ADR-0028）。规则见 {@link RateLimitProperties}，计数见 {@link RateLimiter}。
 *
 * <p><b>为什么排在鉴权之后</b>（{@code HIGHEST_PRECEDENCE + 20}）：已登录的请求要按 user_id 计数，
 * 而 user_id 是 {@link JwtAuthenticationFilter} 放进去的。排在前面的代价是「未登录访问需登录接口」
 * 不会被这一层计数——但那类请求由鉴权层直接 40100 弹回去，不会走到任何业务逻辑，没有滥用面。
 * 真正要防的公开接口（注册、登录）在鉴权层是放行的，所以它们照常按 IP 计数。
 *
 * <p>主体按 ADR-0028：已登录按 {@code u:<userId>}，未登录按 {@code ip:<addr>}。
 * 超限一律 {@code 42900}（交付文档 8.2 的「请求过于频繁」），响应体与其它错误同形状，
 * 所以前端不需要为它写特例。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitProperties properties;
    private final RateLimiter limiter;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(RateLimitProperties properties, RateLimiter limiter, ObjectMapper objectMapper) {
        this.properties = properties;
        this.limiter = limiter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 预检请求不带凭据、也不该被计数：浏览器为每个跨域请求发一次，计进去等于天然刷自己
        return !properties.isEnabled() || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // **必须用归一化路径**：容器给的 getRequestURI() 是未解码的，而路由按解码后的路径走，
        // 用原始 URI 做前缀匹配会被 /%61pi/... 这类编码绕开（实测过，见 RequestPaths 的说明）
        RateLimitProperties.Rule rule = properties.ruleFor(RequestPaths.withinApplication(request));
        if (rule == null) {
            // 没有规则命中 = 不限流。兜底规则（/api/v1/）保证这条分支只对非业务路径生效
            chain.doFilter(request, response);
            return;
        }

        Optional<RateLimiter.Window> window = limiter.hit(rule, subjectOf(request));
        if (window.isEmpty()) {
            // Redis 不可用：放行（ADR-0028「限流器故障时的取舍」一节）
            chain.doFilter(request, response);
            return;
        }
        if (window.get().count() > rule.limit()) {
            writeTooManyRequests(response, window.get());
            return;
        }
        chain.doFilter(request, response);
    }

    /** 已登录按用户，未登录按来源 IP——按 IP 只是「找不到人」时的退路（ADR-0028 的代价一节）。 */
    private String subjectOf(HttpServletRequest request) {
        Long userId = CurrentUser.idOrNull();
        return userId != null ? "u:" + userId : "ip:" + ClientIp.of(request);
    }

    private void writeTooManyRequests(HttpServletResponse response, RateLimiter.Window window) throws IOException {
        // 过滤器在 DispatcherServlet 之前，GlobalExceptionHandler 管不到，得自己写响应体
        // （与 JwtAuthenticationFilter 同样的处理）
        long seconds = Math.max(1, window.retryAfter().toSeconds());
        response.setStatus(ErrorCode.TOO_MANY_REQUESTS.httpStatus().value());
        response.setHeader("Retry-After", Long.toString(seconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiResponse.fail(ErrorCode.TOO_MANY_REQUESTS,
                "请求过于频繁，请 " + seconds + " 秒后再试"));
    }
}
