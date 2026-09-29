package com.pethealth.account.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethealth.account.auth.RateLimiter;
import com.pethealth.account.config.RateLimitProperties;
import com.pethealth.account.metrics.SecurityMetrics;
import com.pethealth.common.error.ErrorCode;
import com.pethealth.common.web.CallerKey;
import com.pethealth.common.web.FilterErrors;
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
    private final SecurityMetrics metrics;

    public RateLimitFilter(RateLimitProperties properties, RateLimiter limiter, ObjectMapper objectMapper,
                           SecurityMetrics metrics) {
        this.properties = properties;
        this.limiter = limiter;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
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

        // 已登录按用户、未登录按来源 IP：规则收在 CallerKey 一处（幂等过滤器用的是同一份）
        Optional<RateLimiter.Window> window = limiter.hit(rule, CallerKey.of(request));
        if (window.isEmpty()) {
            // Redis 不可用：放行（ADR-0028「限流器故障时的取舍」一节）
            chain.doFilter(request, response);
            return;
        }
        if (window.get().count() > rule.limit()) {
            writeTooManyRequests(response, window.get(), rule);
            return;
        }
        chain.doFilter(request, response);
    }

    private void writeTooManyRequests(HttpServletResponse response, RateLimiter.Window window,
                                     RateLimitProperties.Rule rule) throws IOException {
        long seconds = Math.max(1, window.retryAfter().toSeconds());
        // 打点：被拦了多少次、是哪条规则拦的。这是「限流是不是配得太紧」的唯一信号
        metrics.rateLimited(rule.pathPrefix());
        // Retry-After 是**这一条**独有的（契约里承诺给前端做倒计时），所以先设头再写统一信封
        response.setHeader("Retry-After", Long.toString(seconds));
        FilterErrors.write(response, objectMapper, ErrorCode.TOO_MANY_REQUESTS,
                "请求过于频繁，请 " + seconds + " 秒后再试");
    }
}
