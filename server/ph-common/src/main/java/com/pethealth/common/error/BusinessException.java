package com.pethealth.common.error;

/**
 * 业务异常：抛出时带上 {@link ErrorCode}，由 {@code GlobalExceptionHandler} 转成统一响应。
 *
 * <p>约定（见 docs/conventions.md「禁止吞异常」）：业务上可预期的失败一律抛这个，
 * 不要返回 null 让上层猜，也不要把异常吞掉只打日志。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getDefaultMessage());
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /** 资源不存在（含「别人的资源」——越权一律按不存在处理，见 ADR-0012 与契约说明）。 */
    public static BusinessException notFound() {
        return new BusinessException(ErrorCode.NOT_FOUND);
    }

    public static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message);
    }

    public static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message);
    }

    public static BusinessException unauthorized(String message) {
        return new BusinessException(ErrorCode.UNAUTHORIZED, message);
    }

    public static BusinessException paramInvalid(String message) {
        return new BusinessException(ErrorCode.PARAM_INVALID, message);
    }
}
