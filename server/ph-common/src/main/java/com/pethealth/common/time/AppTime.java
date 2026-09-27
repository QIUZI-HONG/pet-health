package com.pethealth.common.time;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 应用统一时区（docs/conventions.md：数据库、后端、前端三处一致 = {@code Asia/Shanghai}）。
 *
 * <p>**不要用 {@code LocalDateTime.now()}**——那取的是 JVM 默认时区，本地跑（WSL 可能是 UTC）、
 * 容器里跑、CI 里跑会得到三个不同的结果。统一走这里。
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
}
