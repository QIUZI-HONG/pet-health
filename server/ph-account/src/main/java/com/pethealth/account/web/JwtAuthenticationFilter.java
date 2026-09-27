package com.pethealth.account.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethealth.account.auth.JwtService;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.error.ErrorCode;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.security.LoginDomain;
import com.pethealth.common.trace.TraceIds;
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
import java.util.Set;

/**
 * 鉴权过滤器（ADR-0012）：解析 {@code Authorization: Bearer}，把身份放进 {@link CurrentUser}。
 *
 * <p>三件事，按顺序：
 *
 * <ol>
 *   <li>有 Token 就校验，成功则写入当前身份与审计用的 operatorId；失败**不立刻报错**，
 *       先记下失败原因，等下面判断这个接口是否需要登录。
 *   <li>需要登录的接口没有有效身份 → 40100；Token 过期 → 40101（前端据此静默刷新）。
 *   <li>登录域必须与接口前缀一致：C 端 Token 调服务商/运营后台接口，等于没登录。
 * </ol>
 *
 * <p>「谁能访问」在这里判，**「这只宠物是不是他的」在业务层判**——前者是身份，后者是归属，
 * 混在一起写会漏（前者拦得住 C 端调后台，拦不住用户 A 读用户 B 的宠物）。
 *
 * <p>顺序排在 TraceId 之后：报错响应要带 traceId，日志也要能串起来。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** 明确不需要登录的接口：注册、登录、换发令牌。 */
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/v1/app/auth/register",
            "/api/v1/app/auth/login",
            "/api/v1/app/auth/refresh");

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(JwtService jwtService, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        BusinessException tokenFailure = authenticate(request, path);

        if (requiresLogin(request, path) && !CurrentUser.isLoggedIn()) {
            // 过期的 Token 给 40101，其它情况给 40100：前端对这两个码的处理完全不同
            writeError(response, tokenFailure != null
                    ? tokenFailure
                    : new BusinessException(ErrorCode.UNAUTHORIZED));
            return;
        }

        try {
            chain.doFilter(request, response);
        } finally {
            // 线程池会复用线程，不清理会把上一个请求的身份带给下一个
            CurrentUser.clear();
        }
    }

    /** 尝试认证；返回失败原因（没有 Token 时返回 null）。 */
    private BusinessException authenticate(HttpServletRequest request, String path) {
        String token = extractToken(request);
        if (token == null) {
            return null;
        }
        try {
            JwtService.AccessToken access = jwtService.parse(token);
            LoginDomain pathDomain = LoginDomain.ofPath(path);
            if (pathDomain != null && access.domain() != pathDomain) {
                // 域不匹配当作没登录，而不是 403：403 等于告诉调用方「这个 Token 是有效的，只是用错了地方」
                return new BusinessException(ErrorCode.UNAUTHORIZED, "当前登录身份不能访问该端的接口");
            }
            CurrentUser.set(access.userId(), access.domain());
            TraceIds.putOperatorId(access.userId());
            return null;
        } catch (BusinessException e) {
            return e;
        }
    }

    private boolean requiresLogin(HttpServletRequest request, String path) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            // 预检请求不带 Authorization，放行交给 CORS 处理
            return false;
        }
        if (!path.startsWith("/api/v1/")) {
            // actuator、error 等非业务路径
            return false;
        }
        if (path.startsWith("/api/v1/open/")) {
            // 外部回调走验签，不走登录（contract/open.yaml）
            return false;
        }
        return !PUBLIC_PATHS.contains(path);
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    private void writeError(HttpServletResponse response, BusinessException e) throws IOException {
        // 过滤器在 DispatcherServlet 之前，GlobalExceptionHandler 管不到这里，得自己写响应体
        response.setStatus(e.getErrorCode().httpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiResponse.fail(e.getErrorCode(), e.getMessage()));
    }
}
