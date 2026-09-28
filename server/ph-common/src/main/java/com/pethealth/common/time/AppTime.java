package com.pethealth.common.time;

import com.pethealth.common.error.BusinessException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * 应用统一时区（docs/conventions.md：数据库、后端、前端三处一致 = {@code Asia/Shanghai}）。
 *
 * <p>**不要用 {@code LocalDateTime.now()}**——那取的是 JVM 默认时区，本地跑（WSL 可能是 UTC）、
 * 容器里跑、CI 里跑会得到三个不同的结果。统一走这里。
 *
 * <p>契约里的日期**字符串**也在这里解析（{@link #parseDate}）：时区和日期口径收在一处，
 * 免得每个 service 各写一遍 {@code LocalDate.parse}。
 */
public final class AppTime {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private AppTime() {
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(ZONE);
    }

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    /**
     * 解析契约里的日期字符串（{@code YYYY-MM-DD}）。
     *
     * <p>为什么不直接用 {@code LocalDate.parse}：它对**格式对但日子不存在**的输入
     * （{@code 2026-02-31}）抛的是 {@code DateTimeParseException}，落进兜底处理器就是 50000——
     * 用户看到「服务器内部错误」，而正确答复是 40001「这个日期不存在」。
     * Bean Validation 的正则只校形状，拦不住这一类（2026-09-28 测试报告 D11）。
     */
    public static LocalDate parseDate(String raw) {
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException | NullPointerException e) {
            throw BusinessException.paramInvalid("日期格式不正确，应为 YYYY-MM-DD 且是真实存在的日期");
        }
    }
}
