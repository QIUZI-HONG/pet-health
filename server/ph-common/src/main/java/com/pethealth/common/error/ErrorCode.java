package com.pethealth.common.error;

import org.springframework.http.HttpStatus;

/**
 * 业务错误码。数值与文案来自交付文档 8.2，契约里同步维护在 contract/common.yaml 的 {@code x-error-codes}。
 *
 * <p><b>加新码要同时改三处</b>：这里、契约、交付文档的对照说明——漏掉契约 CI 不会红（它只校验类型生成物），
 * 所以这条靠评审盯。
 */
public enum ErrorCode {

    SUCCESS(0, "success", HttpStatus.OK),

    PARAM_INVALID(40001, "参数错误", HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(40100, "未登录", HttpStatus.UNAUTHORIZED),
    TOKEN_EXPIRED(40101, "Token 已过期", HttpStatus.UNAUTHORIZED),
    FORBIDDEN(40300, "无权限", HttpStatus.FORBIDDEN),
    NOT_FOUND(40400, "资源不存在", HttpStatus.NOT_FOUND),
    CONFLICT(40900, "数据冲突", HttpStatus.CONFLICT),
    TOO_MANY_REQUESTS(42900, "请求过于频繁", HttpStatus.TOO_MANY_REQUESTS),
    SERVER_ERROR(50000, "服务器内部错误", HttpStatus.INTERNAL_SERVER_ERROR),
    SERVICE_UNAVAILABLE(50300, "服务不可用", HttpStatus.SERVICE_UNAVAILABLE),

    // AI 的降级是硬要求：超时与失败都不向上抛错，前端按文案提示（contract/common.yaml 的 AiTimeout / AiFailed）
    AI_TIMEOUT(60001, "AI 服务超时", HttpStatus.OK),
    AI_FAILED(60002, "AI 分析失败", HttpStatus.OK),

    // 70001 / 70002（支付失败 / 订单已支付）暂时不定义：ADR-0002 定了不做线上收款，
    // 这两个码的语义等支付澄清后再定（见地图 #52 的 Notes 首节）。
    // 下面这些是**业务结果**而不是传输层失败，所以一律 200——前端按 code 展示文案
    COUPON_UNAVAILABLE(80001, "券不可用", HttpStatus.OK),
    COUPON_REDEEMED(80002, "券已核销", HttpStatus.OK),
    PRICE_OUT_OF_RANGE(90001, "价格超出区间", HttpStatus.OK);

    private final int code;
    private final String defaultMessage;
    private final HttpStatus httpStatus;

    ErrorCode(int code, String defaultMessage, HttpStatus httpStatus) {
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.httpStatus = httpStatus;
    }

    public int getCode() {
        return code;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }

    /** 对应的 HTTP 状态码；异常处理器与过滤器共用这一份映射，不各写一套。 */
    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
