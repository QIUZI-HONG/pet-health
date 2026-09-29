package com.pethealth.account.web;

import com.pethealth.common.trace.TraceIds;
import com.pethealth.common.web.CallerKey;
import com.pethealth.common.web.RequestPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 访问日志：每个业务请求一行（ADR-0029）。
 *
 * <p>为什么需要它：选了「只落日志 + 按 traceId 检索」这条路之后，**每个请求至少要有一行日志**，
 * 否则「追一次请求」根本无从下手。实测发现本仓大多数成功路径压根不打日志——
 * 一次成功的 AI 咨询、一次成功的读档案，在该 traceId 下命中 0 行。
 * 只有失败与关键写操作才留痕，而排查时恰恰常需要知道「成功的那次发生了什么」。
 *
 * <p>为什么不用 Tomcat 的 access log：它只能写文件（没有 stdout 模式），而容器化部署里
 * 「写 stdout 交给采集」才是我们要的形态。用 SLF4J 还顺带拿到了 MDC 里的 traceId，
 * 不必依赖响应头。
 *
 * <p>排在**鉴权之前**（{@code HIGHEST_PRECEDENCE + 1}）：这样无论请求是被限流、被拒还是走通，
 * 都会留下痕迹，耗时也从最早处开始算。只记 {@code /api/} 下的路径——
 * 探针与 actuator 的噪音不该淹掉业务日志。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("ph.access");

    /** 慢请求单独标出来：这一行是三处排查（谁、做了什么、多慢）里唯一按时间排序的入口。 */
    private static final long SLOW_MS = 1000;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = RequestPaths.withinApplication(request);
        return !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long millis = (System.nanoTime() - started) / 1_000_000;
            // 一次请求一行，结构化到「键=值」：采集层不必解析自由文本就能按状态码/耗时筛
            // （traceId 由日志 pattern 从 MDC 打到行首，这里不重复写）
            log.info("method={} path={} status={} duration_ms={} caller={} trace_id={}",
                    request.getMethod(), RequestPaths.withinApplication(request),
                    response.getStatus(), millis, CallerKey.of(request), TraceIds.currentTraceId());
            if (millis >= SLOW_MS && !isAiConsult(request)) {
                // AI 咨询天然慢（模型延迟主导，ADR-0017 记过 20 秒），单独有阈值，别混进来
                log.warn("慢请求 method={} path={} duration_ms={}",
                        request.getMethod(), RequestPaths.withinApplication(request), millis);
            }
        }
    }

    private static boolean isAiConsult(HttpServletRequest request) {
        return RequestPaths.withinApplication(request).endsWith("/ai-consults");
    }
}
