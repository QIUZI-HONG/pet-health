package com.pethealth.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.UrlPathHelper;

/**
 * 取「路由用的那个路径」。
 *
 * <p><b>为什么不能直接用 {@code request.getRequestURI()}</b>：那是容器给的**未解码**原始 URI，
 * 而 Tomcat 是按**解码后**的路径做路由的（Spring MVC 的 handler 映射同理）。两者不一致时，
 * 前缀匹配类的判断会被编码绕过：{@code /%61pi/v1/app/auth/register} 能进到注册控制器，
 * 却匹配不上 {@code /api/v1/app/auth/} 这条前缀——限流与「这个接口要不要登录」都会被绕开。
 * 这是实测过的绕过（在 Tomcat 上验过 servlet 确实被调用），不是理论担忧。
 *
 * <p>所以这里统一走 Spring 自己的 {@link UrlPathHelper}：**与 Spring MVC 解析 handler 时用的是同一套
 * 归一化规则**（解码、去分号内容、应用上下文内路径）。只要两边同源，就不会再出现
 * 「路由进了 A，规则以为是 B」这类偏差。
 *
 * <p>用一个统一的入口而不是各处自己写，是因为这个坑踩过一次就够了：任何新的过滤器/拦截器
 * 只要判断路径，都该走这里。
 */
public final class RequestPaths {

    private RequestPaths() {
    }

    /**
     * 应用上下文内的归一化路径（已解码、已去 {@code ;jsessionid} 之类的分号内容）。
     *
     * @return 形如 {@code /api/v1/app/auth/login}；拿不到时回退成本次请求的原始 URI，
     *         宁可退回旧行为也不要返回空串——空串会让前缀匹配全部失效，等于放开所有规则
     */
    public static String withinApplication(HttpServletRequest request) {
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        if (path == null || path.isEmpty()) {
            return request.getRequestURI() == null ? "" : request.getRequestURI();
        }
        return path;
    }
}
