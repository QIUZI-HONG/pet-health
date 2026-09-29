package com.pethealth.boot.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.read.ListAppender;
import com.pethealth.boot.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 日志行带 traceId（ADR-0029）：「按 request_id 全链路追踪」能不能成立，取决于日志行本身带不带它。
 *
 * <p>为什么要测到这个程度：改之前实测过「一次真实启动 + 若干请求，451 行日志 0 行带 traceId」
 * （Spring Boot 默认格式不含 MDC，而本仓原先没有 logging 配置）。
 * 这类缺口**没有任何功能症状**——功能全对，只是出事那天检索不出来。
 *
 * <p>断言方式是：**走真实日志路径**（真 logger 记一条 → 用 ListAppender 捕获 → 用运行时真正生效的
 * pattern 格式化），而不是断言「配置文件里有 %X{traceId}」。后者在 pattern 被换掉、
 * 占位符拼错、或 MDC 键名改了时都可能通过。
 */
@DisplayName("日志 traceId（ADR-0029）")
class LogTraceIdTest extends IntegrationTestBase {

    private static final String TRACE_KEY = "traceId";
    private static final String TEST_LOGGER = "trace-id-test";

    private final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();

    @AfterEach
    void detach() {
        context.getLogger(TEST_LOGGER).detachAppender(captured);
        MDC.remove(TRACE_KEY);
    }

    /** 运行时真正生效的 console pattern。 */
    private String effectiveConsolePattern() {
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        Object appender = root.getAppender("CONSOLE");
        assertThat(appender).as("找不到 CONSOLE appender，测试前提不成立").isNotNull();
        Object encoder = ((ConsoleAppender<?>) appender).getEncoder();
        assertThat(encoder).as("console 的编码器应当是 pattern 布局").isInstanceOf(PatternLayoutEncoder.class);
        return ((PatternLayoutEncoder) encoder).getPattern();
    }

    /** 记一条日志，返回按生效 pattern 格式化后的那一行。 */
    private String loggedLine(String message) {
        captured.list.clear();
        context.getLogger(TEST_LOGGER).info(message);
        assertThat(captured.list).as("应当捕获到一条日志").hasSize(1);

        PatternLayout layout = new PatternLayout();
        layout.setContext(context);
        layout.setPattern(effectiveConsolePattern());
        layout.start();
        try {
            return layout.doLayout(captured.list.get(0));
        } finally {
            layout.stop();
        }
    }

    private void attachAppender() {
        captured.setContext(context);
        captured.start();
        Logger logger = context.getLogger(TEST_LOGGER);
        logger.setLevel(Level.INFO);
        logger.addAppender(captured);
    }

    @Test
    @DisplayName("MDC 里有 traceId 时，日志行必须带上它")
    void traceIdIsPartOfEveryLogLine() {
        attachAppender();
        String traceId = "trace-for-log-test-0001";
        MDC.put(TRACE_KEY, traceId);

        String line = loggedLine("一条普通的业务日志");

        assertThat(line)
                .as("生效的 pattern 是：" + effectiveConsolePattern())
                .contains(traceId)
                .contains("一条普通的业务日志");
    }

    @Test
    @DisplayName("没有 traceId 时留空，不出现 null 之类的假值（启动日志与定时任务不该被塞噪音）")
    void withoutTraceIdNothingIsFaked() {
        attachAppender();

        String line = loggedLine("启动日志");

        assertThat(line).contains("[]").doesNotContain("null");
    }

    @Test
    @DisplayName("每个业务请求都留一行访问日志，且那一行能按 traceId 检索")
    void everyRequestLeavesOneTraceableLine() {
        // 为什么这条重要：选了「只落日志 + 按 traceId 检索」之后，**每个请求至少要有一行**，
        // 否则「追一次请求」无从下手。原先大多数成功路径压根不打日志——
        // 实测一次成功的 AI 咨询在该 traceId 下命中 0 行
        String traceId = "trace-access-log-0001";
        String path = "/api/v1/app/users/me";

        Logger access = context.getLogger("ph.access");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        access.setLevel(Level.INFO);
        access.addAppender(appender);
        try {
            var headers = new org.springframework.http.HttpHeaders();
            headers.set("X-Request-Id", traceId);
            rest.exchange(java.net.URI.create(rest.getRootUri() + path),
                    org.springframework.http.HttpMethod.GET,
                    new org.springframework.http.HttpEntity<>(headers), String.class);

            ILoggingEvent line = appender.list.stream()
                    .filter(e -> e.getFormattedMessage().contains("path=" + path))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "没有访问日志行。捕获到 " + appender.list.size() + " 条：" + appender.list));

            // 这一行要能回答「谁、做了什么、多慢」；traceId 在 MDC 里，
            // 由 logging.pattern 打到行首，所以整行可按它检索
            assertThat(line.getFormattedMessage())
                    .contains("method=GET").contains("status=401").contains("duration_ms=");
            assertThat(line.getMDCPropertyMap()).as("访问日志行必须带 traceId，否则串不起链路")
                    .containsEntry(TRACE_KEY, traceId);
        } finally {
            access.detachAppender(appender);
        }
    }
}
