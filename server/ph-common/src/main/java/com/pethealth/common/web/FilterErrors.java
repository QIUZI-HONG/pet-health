package com.pethealth.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 过滤器层写错误响应。
 *
 * <p>为什么单独抽出来：过滤器跑在 DispatcherServlet **之前**，{@code GlobalExceptionHandler}
 * 管不到它们，每个过滤器都得自己拼响应体。原先三处各写一遍（鉴权、限流、幂等），
 * 形状一模一样——这类重复最容易出现的后果是改了一处漏另一处（比如给错误响应补一个头时）。
 *
 * <p>只负责「写成与业务接口同一个信封」，不决定状态码与文案——那两样是各过滤器自己的语义。
 */
public final class FilterErrors {

    private FilterErrors() {
    }

    /**
     * 把错误写进响应：同步 HTTP 状态码、JSON 内容类型、UTF-8，响应体是全局统一的 {@link ApiResponse} 信封。
     *
     * @param response 目标响应
     * @param mapper   用容器里那个配置好的 ObjectMapper（全局 SNAKE_CASE 在那上面）
     * @param code     业务错误码；它的 {@code httpStatus()} 会一并写进状态行
     * @param message  给用户看的一句话（契约里 {@code message} 是「前端直接展示」的文案）
     */
    public static void write(HttpServletResponse response, ObjectMapper mapper,
                             ErrorCode code, String message) throws IOException {
        response.setStatus(code.httpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getWriter(), ApiResponse.fail(code, message));
    }
}
