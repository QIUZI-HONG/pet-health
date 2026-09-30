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

    // AI 的降级**不产生错误码**：一律 HTTP 200 + `degraded=true` + 按 `degrade_code` 映射的一句中文
    // （ADR-0021 / ADR-0026）。交付文档 8.2 的 60001 / 60002 因此不再定义——理由与后续触发点
    // 写在 ADR-0026，别把它们加回来。

    // 70001 / 70002（支付失败 / 订单已支付）暂时不定义：ADR-0002 定了不做线上收款，
    // 这两个码的语义等支付澄清后再定（见地图 #52 的 Notes 首节）。
    // 下面三个是**业务结果**而不是传输层失败，所以一律 200——前端按 code 展示文案。
    // 产出路径（2026-09-30 清点，别再按「没有产出」读它们）：
    //   80001/80002 —— 券的占用与核销冲突（ph-privilege 的 AppGrowthConsole 锁券那一支）；
    //   90001       —— 服务者定价越界（ph-catalog 的 CatalogQueryService，message 里带区间文案）。
    COUPON_UNAVAILABLE(80001, "券不可用", HttpStatus.OK),
    COUPON_REDEEMED(80002, "券已核销", HttpStatus.OK),
    PRICE_OUT_OF_RANGE(90001, "价格超出区间", HttpStatus.OK),

    /**
     * 订单已固化：**报工提交之后，这一单的照片与备注不再可改**（ADR-0049 §一，项目所有者拍板）。
     *
     * <p>为什么不复用 40900：40900 在这一族里有两层意思（「还没到能写的状态」与「已经封存」），
     * 前端要靠 message 猜是哪一种。分开之后语义唯一：**40900 = 现在还不能写**
     * （待接单 / 已预约 / 已取消），**40901 = 已经封存，要改只能走运营干预**。
     *
     * <p><b>契约侧已补齐</b>：{@code contract/common.yaml} 的 {@code x-error-codes} 里有 40901
     * （「已终结不可改（报工后固化、修正须走运营干预）」），前端可以按码分支，不必猜 message。
     * 补这一行的是契约写入者（ADR-0047 的流程）；本条原先写着「待补一行」，契约补上之后
     * 那句话就反过来成了误导——注释与实现相反正是最容易让后来者绕开正确路径的一类缺陷。
     */
    ORDER_FINALIZED(40901, "订单已固化，不能再修改", HttpStatus.CONFLICT);

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
