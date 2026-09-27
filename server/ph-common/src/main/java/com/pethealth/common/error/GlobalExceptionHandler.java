package com.pethealth.common.error;

import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.trace.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;

/**
 * 全局异常处理：把异常统一转成 {@link ApiResponse}（交付文档 8.1 / 8.2）。
 *
 * <p>两条纪律：
 *
 * <ul>
 *   <li><b>禁止吞异常</b>（docs/conventions.md）：未预期的异常一律记 error 日志（带 traceId）再返回 50000，
 *       不把堆栈丢给前端。
 *   <li><b>HTTP 状态码与业务码都给出</b>：契约里写了 401 / 404 / 409 等状态码，前端做重试与跳转时两者都用得上。
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e, HttpServletRequest request) {
        // 业务异常是预期内的分支，不打堆栈，留一行 warn 便于排查
        log.warn("业务异常 code={} path={} traceId={} message={}",
                e.getErrorCode().getCode(), request.getRequestURI(), TraceIds.currentTraceId(), e.getMessage());
        return ResponseEntity.status(e.getErrorCode().httpStatus())
                .body(ApiResponse.fail(e.getErrorCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(this::describe)
                .collect(Collectors.joining("; "));
        return badRequest(detail);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .collect(Collectors.joining("; "));
        return badRequest(detail);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<ApiResponse<Void>> handleMalformedRequest(Exception e) {
        return badRequest("请求参数无法解析：" + e.getMessage());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.fail(ErrorCode.PARAM_INVALID, "请求方法不支持：" + e.getMethod()));
    }

    /**
     * 路径不存在。Spring 6.1 起不再抛 NoHandlerFoundException，静态资源兜底会抛这个——
     * 不特判就会变成一个吓人的 500。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail(ErrorCode.NOT_FOUND, "接口不存在：" + e.getResourcePath()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("未预期异常 path={} traceId={}", request.getRequestURI(), TraceIds.currentTraceId(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ErrorCode.SERVER_ERROR, ErrorCode.SERVER_ERROR.getDefaultMessage()));
    }

    private ResponseEntity<ApiResponse<Void>> badRequest(String detail) {
        String message = (detail == null || detail.isBlank())
                ? ErrorCode.PARAM_INVALID.getDefaultMessage()
                : detail;
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(ErrorCode.PARAM_INVALID, message));
    }

    private String describe(FieldError error) {
        return error.getField() + " " + error.getDefaultMessage();
    }
}
