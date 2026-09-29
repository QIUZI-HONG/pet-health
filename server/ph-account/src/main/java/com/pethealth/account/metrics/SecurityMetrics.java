package com.pethealth.account.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 安全机制自身的指标（ADR-0028 的「可观测」一半）。
 *
 * <p>为什么要有它：这三道机制（限流、审计、幂等）在**正常工作时完全静默**——
 * 被限流的请求返回 429、审计写失败只留一行 WARN、幂等重放照常返回 200。
 * 没有指标时，唯一发现「限流打满」「审计写不进去」的办法是人去看日志（ADR-0028 原先就是这么写的）。
 * 暴露成计数器之后，告警规则只要盯这几个名字即可。
 *
 * <p><b>命名是暂定的</b>：指标的完整清单与告警阈值属 #88（可观测性）那张决策票，
 * 这里只给本模块自己产生的三类事件起名，前缀统一 {@code ph.security.}，
 * 等 #88 定了口径再对齐（改名成本主要在面板，不在代码）。
 *
 * <p>不用 Gauge/直方图：这里只关心「发生过多少次」，计数就够。
 */
@Component
public class SecurityMetrics {

    /** 被限流挡下的请求数。按规则前缀打标签，才能看出是哪条规则在拦。 */
    public static final String RATE_LIMITED = "ph.security.rate_limit.rejected";

    /** 幂等重放的请求数（同一个键第二次来，服务端没有真的执行）。 */
    public static final String IDEMPOTENT_REPLAYED = "ph.security.idempotency.replayed";

    /** 幂等冲突数：在途（40900）或同键不同体（40001）。这两个数持续增长通常意味着客户端在重试风暴里。 */
    public static final String IDEMPOTENCY_CONFLICT = "ph.security.idempotency.conflict";

    /**
     * 审计写入失败的次数。
     *
     * <p>**这个是四个里最该配告警的**：审计失败不阻断业务（ADR-0028 的取舍），
     * 所以它不会有任何面向用户的症状——只有这个指标能告诉你「审计这一层没在记了」。
     */
    public static final String AUDIT_WRITE_FAILED = "ph.security.audit.write_failed";

    private final MeterRegistry registry;

    public SecurityMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 记一次限流拦截。 */
    public void rateLimited(String rulePrefix) {
        counter(RATE_LIMITED, "rule", rulePrefix).increment();
    }

    /** 记一次幂等重放（这次请求没有真的执行）。 */
    public void idempotentReplayed() {
        counter(IDEMPOTENT_REPLAYED, null, null).increment();
    }

    /**
     * 记一次幂等冲突。
     *
     * @param reason {@code in_flight}（上一次还在处理）或 {@code fingerprint_mismatch}（同键不同体）
     */
    public void idempotencyConflict(String reason) {
        counter(IDEMPOTENCY_CONFLICT, "reason", reason).increment();
    }

    /** 记一次审计写入失败。 */
    public void auditWriteFailed() {
        counter(AUDIT_WRITE_FAILED, null, null).increment();
    }

    /**
     * 取（或创建）计数器。标签为 null 时不打标签——Micrometer 不接受 null 的 tag 值，
     * 所以这里必须分支，不能把 null 传下去。
     */
    private Counter counter(String name, String tagKey, String tagValue) {
        return tagKey == null
                ? Counter.builder(name).register(registry)
                : Counter.builder(name).tag(tagKey, tagValue).register(registry);
    }
}
