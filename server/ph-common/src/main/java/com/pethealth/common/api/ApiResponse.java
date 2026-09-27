package com.pethealth.common.api;

import com.pethealth.common.error.ErrorCode;
import com.pethealth.common.trace.TraceIds;

/**
 * 统一响应体：{@code {code, message, data, request_id}}（交付文档 8.1，契约见 contract/common.yaml）。
 *
 * <p>{@code code} 为 0 表示成功，非 0 是 {@link ErrorCode} 里的业务错误码。
 * HTTP 状态码由 {@code GlobalExceptionHandler} 统一决定——前端按 {@code code} 判断，不看 HTTP 码。
 *
 * <p>字段名转 snake_case 由全局 Jackson 配置负责（ADR-0011），这里不逐个标注，
 * 免得出现「一半靠注解、一半靠配置」的两套写法。
 */
public record ApiResponse<T>(int code, String message, T data, String requestId) {

    private static final String SUCCESS_MESSAGE = "success";

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), SUCCESS_MESSAGE, data, TraceIds.currentTraceId());
    }

    /** 无返回数据的成功响应（登出、删除等）。 */
    public static ApiResponse<Void> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.getCode(), message, null, TraceIds.currentTraceId());
    }
}
