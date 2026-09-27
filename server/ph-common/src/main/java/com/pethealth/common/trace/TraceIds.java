package com.pethealth.common.trace;

import org.slf4j.MDC;

/**
 * 全链路追踪 ID 的载体。
 *
 * <p>{@code traceId} 要求贯穿 Java → Python AI 服务 → 模型调用（ADR-0009），
 * 所以它既进日志 MDC，也进每条写记录的 {@code trace_id} 列（ADR-0011）。
 * {@code operatorId} 是当前请求的操作者，供审计字段填充使用；系统自身写入记为 0。
 */
public final class TraceIds {

    /** 请求头与响应头用同一个名字：调用方带自己的 ID 进来，响应里再回带，便于跨系统串联。 */
    public static final String HEADER = "X-Request-Id";
    public static final String RESPONSE_HEADER = HEADER;

    private static final String TRACE_KEY = "traceId";
    private static final String OPERATOR_KEY = "operatorId";

    /** 系统写入（定时任务、AI 回流等没有登录用户的操作）。 */
    public static final long SYSTEM_OPERATOR_ID = 0L;

    private TraceIds() {
    }

    public static String currentTraceId() {
        String traceId = MDC.get(TRACE_KEY);
        return traceId == null ? "" : traceId;
    }

    public static void putTraceId(String traceId) {
        MDC.put(TRACE_KEY, traceId);
    }

    public static void putOperatorId(long operatorId) {
        MDC.put(OPERATOR_KEY, Long.toString(operatorId));
    }

    public static long currentOperatorId() {
        String value = MDC.get(OPERATOR_KEY);
        if (value == null || value.isBlank()) {
            return SYSTEM_OPERATOR_ID;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            // 走到这里说明有人往 MDC 里塞了非数字，按系统写入处理而不是让写操作失败
            return SYSTEM_OPERATOR_ID;
        }
    }

    public static void clear() {
        MDC.remove(TRACE_KEY);
        MDC.remove(OPERATOR_KEY);
    }
}
