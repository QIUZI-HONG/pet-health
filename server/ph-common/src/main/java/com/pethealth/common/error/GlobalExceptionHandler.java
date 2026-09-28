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
import java.util.List;
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

    /**
     * 请求体字段校验失败。
     *
     * <p>**返回给用户的只有注解自带的那句中文**（例如「密码需 8–32 位，且同时包含字母与数字」），
     * 不再拼上英文字段键——契约说 {@code message} 是「前端直接展示」的文案，
     * 而「weight 体重需小于 1000」这种把内部标识甩给用户的消息，是 2026-09-28 测试报告里的 D25。
     * 字段名进日志，排查时照样能对上。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e,
                                                              HttpServletRequest request) {
        List<FieldError> errors = e.getBindingResult().getFieldErrors();
        log.info("参数校验失败 path={} fields={} traceId={}", request.getRequestURI(),
                errors.stream().map(FieldError::getField).collect(Collectors.joining(",")),
                TraceIds.currentTraceId());
        String detail = errors.stream()
                .map(FieldError::getDefaultMessage)
                .filter(message -> message != null && !message.isBlank())
                .collect(Collectors.joining("；"));
        return badRequest(detail);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException e,
                                                                      HttpServletRequest request) {
        log.info("参数校验失败（方法级）path={} violations={} traceId={}", request.getRequestURI(),
                e.getConstraintViolations().size(), TraceIds.currentTraceId());
        String detail = e.getConstraintViolations().stream()
                .map(v -> v.getMessage())
                .filter(message -> message != null && !message.isBlank())
                .collect(Collectors.joining("；"));
        return badRequest(detail);
    }

    /**
     * 参数缺失、类型不对、请求体不是合法 JSON。
     *
     * <p>三类分开给话术：缺参数与取值不合法是**调用方自己的问题**，把参数名（契约里的名字）告诉它
     * 才帮得上忙；而框架的原始异常消息里带内部类名、字段路径、Java 参数名，一律只进日志。
     */
    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<ApiResponse<Void>> handleMalformedRequest(Exception e, HttpServletRequest request) {
        log.info("请求无法解析 path={} traceId={} detail={}", request.getRequestURI(),
                TraceIds.currentTraceId(), e.getMessage());
        if (e instanceof MissingServletRequestParameterException missing) {
            return badRequest("缺少必填参数：" + missing.getParameterName());
        }
        if (e instanceof MethodArgumentTypeMismatchException mismatch) {
            return badRequest("参数 " + mismatch.getName() + " 的取值不合法");
        }
        return badRequest("请求体格式不正确（不是合法的 JSON）");
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
}
