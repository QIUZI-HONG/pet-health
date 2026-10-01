package com.pethealth.account.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethealth.account.auth.AccountStatus;
import com.pethealth.account.auth.JwtService;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.error.ErrorCode;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.security.LoginDomain;
import com.pethealth.common.trace.TraceIds;
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
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 鉴权过滤器（ADR-0012）：解析 {@code Authorization: Bearer}，把身份放进 {@link CurrentUser}。
 *
 * <p>三件事，按顺序：
 *
 * <ol>
 *   <li>有 Token 就校验，成功则写入当前身份与审计用的 operatorId；失败**不立刻报错**，
 *       先记下失败原因，等下面判断这个接口是否需要登录。
 *   <li>需要登录的接口没有有效身份 → 40100；Token 过期 → 40101（前端据此静默刷新）。
 *   <li>登录域必须与接口前缀一致：C 端 Token 调服务者后台/运营后台接口，等于没登录。
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

    /**
     * 明确不需要登录的接口：注册、登录、换发令牌——**三个登录域各一套**
     * （ADR-0012：服务者后台与运营后台是独立登录域，所以它们各有自己的登录入口，
     * 不存在「在 C 端登录后拿同一枚令牌进后台」这条路）。
     */
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/v1/app/auth/register",
            "/api/v1/app/auth/login",
            "/api/v1/app/auth/refresh",
            "/api/v1/provider/auth/register",
            "/api/v1/provider/auth/login",
            "/api/v1/provider/auth/refresh",
            "/api/v1/provider/auth/logout",
            "/api/v1/admin/auth/register",
            "/api/v1/admin/auth/login",
            "/api/v1/admin/auth/refresh",
            "/api/v1/admin/auth/logout");

    /**
     * 免登录的**只读浏览**接口（ADR-0037 第一节：游客不是角色，只读接口不需要身份）。
     *
     * <p>写成「方法 + 锚定正则」而不是加进 {@link #PUBLIC_PATHS}：那是个精确字符串集合，
     * 装不下带路径参数的详情路径。也**不能用前缀**——它与
     * {@code /api/v1/app/providers/{id}/appointment-slots}（那条要登录）同前缀，
     * 前缀放行会把号源查询一起打开。正则两端锚定、且**只认 GET**：
     * 将来在同名路径上加一个写接口时，默认是「要登录」而不是「跟着放行」。
     *
     * <p>目前是「找店 / 看店」「目录浏览」与「合规条款」三组。往这里加一条之前先问一句：
     * 它返回的东西里有没有**只对某个身份可见**的字段？有就不属于这里。
     */
    private static final List<PublicReadRoute> PUBLIC_READ_ROUTES = List.of(
            new PublicReadRoute("GET", Pattern.compile("^/api/v1/app/providers$")),
            new PublicReadRoute("GET", Pattern.compile("^/api/v1/app/providers/[^/]+$")),
            new PublicReadRoute("GET", Pattern.compile("^/api/v1/app/catalog/categories$")),
            new PublicReadRoute("GET", Pattern.compile("^/api/v1/app/catalog/items$")),
            new PublicReadRoute("GET", Pattern.compile("^/api/v1/app/catalog/items/[^/]+/providers$")),
            // 合规条款：**必须在注册前可读**——不然「我已阅读并同意」没有依据（交付文档 2.5、
            // 2026-09-30 验收的 F027）。两份都是平台的对外文书，没有只对某个身份可见的字段。
            // 清单与详情一起放行：清单里除了标题就是这三份的名字，只放详情会让「列表要登录、
            // 详情不要」变成一条没理由的差异。
            new PublicReadRoute("GET", Pattern.compile("^/api/v1/app/compliance/documents$")),
            new PublicReadRoute("GET", Pattern.compile("^/api/v1/app/compliance/documents/[^/]+$")));

    /** 一条免登录的只读路由。 */
    private record PublicReadRoute(String httpMethod, Pattern pattern) {

        boolean matches(String method, String path) {
            return httpMethod.equalsIgnoreCase(method) && pattern.matcher(path).matches();
        }
    }

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;
    private final AccountStatus accountStatus;

    public JwtAuthenticationFilter(JwtService jwtService, ObjectMapper objectMapper,
                                   AccountStatus accountStatus) {
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
        this.accountStatus = accountStatus;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // **用归一化路径**，与路由（和限流过滤器）同一口径：容器给的 getRequestURI() 是未解码的，
        // 拿它做前缀判断会被 /%61pi/... 这类编码绕开「这个接口要不要登录」这一问（RequestPaths 有说明）
        String path = RequestPaths.withinApplication(request);
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
            // 已注销/禁用的账号等于未登录：判在鉴权层**这一处**，业务代码就不必各自记得查状态。
            // 注销时签发过的 Access Token 在到期前签名仍有效，靠这一句作废（ADR-0012 的补充）
            if (!accountStatus.isActive(access.userId())) {
                return new BusinessException(ErrorCode.UNAUTHORIZED, "账号已注销或禁用");
            }
            CurrentUser.set(access.userId(), access.domain());
            TraceIds.putOperatorId(access.userId());
            return null;
        } catch (BusinessException e) {
            return e;
        }
    }

    /**
     * 这个接口要不要登录：**默认要**，只有四类放行——预检请求（交给 CORS）、非
     * {@code /api/v1/} 路径（actuator、error）、{@code /api/v1/open/} 的外部回调（走验签，contract/open.yaml），
     * 再去掉 {@link #PUBLIC_PATHS} 那三条精确路径与 {@link #PUBLIC_READ_ROUTES} 那两条只读浏览。
     *
     * <p>三个坑：{@code path} 必须是归一化后的（理由见 {@code doFilterInternal} 里那句），
     * 拿 {@code getRequestURI()} 判会被编码绕开；免登录表是**精确相等**而不是前缀——
     * 改成 {@code startsWith} 会把 {@code /api/v1/app/auth/loginXXX} 一起放行；
     * 只读浏览那两条与 {@code …/providers/{id}/appointment-slots}（要登录）**同前缀**，
     * 所以它们同样是锚定匹配，不是前缀。
     *
     * <p>这里只判「有没有身份」，「这只宠物/这条订单是不是他的」在业务层判（ADR-0012，类注释）。
     */
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
        if (isPublicRead(request.getMethod(), path)) {
            return false;
        }
        return !PUBLIC_PATHS.contains(path);
    }

    /** 这个请求是不是「免登录的只读接口」。 */
    private static boolean isPublicRead(String method, String path) {
        return PUBLIC_READ_ROUTES.stream().anyMatch(route -> route.matches(method, path));
    }

    /** 从 {@code Authorization: Bearer <token>} 里取出 token，没有就 null。**只提取、不验签**
     *  （验签与登录域比对在 {@link #authenticate}）；空串按「没有 Token」处理——前端很容易塞一个空字符串。
     *  前缀按字面比较（大小写敏感），别按 HTTP 里 scheme 大小写不敏感的直觉去用。 */
    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /** 手写错误响应体（形状与全局异常处理一致，走 {@code FilterErrors}）。过滤器排在 MVC 之前，
     *  这里抛出的异常**不会**被 {@code @RestControllerAdvice} 接住——所以只能自己写。 */
    private void writeError(HttpServletResponse response, BusinessException e) throws IOException {
        FilterErrors.write(response, objectMapper, e.getErrorCode(), e.getMessage());
    }
}
