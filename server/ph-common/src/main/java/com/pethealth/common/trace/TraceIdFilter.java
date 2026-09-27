package com.pethealth.common.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 给每个请求分配 traceId：优先用调用方传来的 {@code X-Request-Id}，没有就生成一个。
 *
 * <p>顺序在最前（{@link Ordered#HIGHEST_PRECEDENCE}），因为下游的鉴权过滤器、审计字段填充、
 * 日志、异常处理都依赖它已经就位。响应里也回带同一个 ID，前端报错截图就能定位到日志。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /** 调用方传来的 ID 长度上限，防止有人塞个几 KB 的值进来撑爆日志。 */
    private static final int MAX_TRACE_ID_LENGTH = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = resolveTraceId(request.getHeader(TraceIds.HEADER));
        TraceIds.putTraceId(traceId);
        response.setHeader(TraceIds.RESPONSE_HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            // 线程会被复用，不清干净会把 traceId 带给下一个请求
            TraceIds.clear();
        }
    }

    private String resolveTraceId(String fromHeader) {
        if (fromHeader == null || fromHeader.isBlank() || fromHeader.length() > MAX_TRACE_ID_LENGTH) {
            return UUID.randomUUID().toString().replace("-", "");
        }
        return fromHeader;
    }
}
