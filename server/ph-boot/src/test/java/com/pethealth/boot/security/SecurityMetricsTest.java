package com.pethealth.boot.security;

import com.pethealth.account.metrics.SecurityMetrics;
import com.pethealth.boot.support.ApiClient;
import com.pethealth.boot.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethealth.account.domain.AuditLog;
import com.pethealth.account.mapper.AuditLogMapper;
import com.pethealth.account.service.AuditRecorder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.transaction.PlatformTransactionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 安全机制的指标确实会动（ADR-0028 的可观测一半）。
 *
 * <p>为什么值得单独测：指标**没接上**与「没发生过」在面板上长得一模一样——
 * 都是 0。没这层断言的话，一次打点写错名字（比如标签拼错）不会有任何症状，
 * 直到出事那天才发现面板是空的。
 *
 * <p>在默认上下文里跑（与大多数用例同一个 Spring 上下文，不额外付启动成本）。
 * 计数器是**单调累加**的：`cleanDatabase` 会清库与 Redis，但**不清注册表**
 * （那是进程级的），所以断言写「增加」而不是「等于 1」。
 */
@DisplayName("安全指标（ADR-0028）")
class SecurityMetricsTest extends IntegrationTestBase {

    private static final String PHONE = "13900005001";

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private SecurityMetrics metrics;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private ApiClient api;

    @BeforeEach
    void setUpClient() {
        api = new ApiClient(rest, objectMapper);
    }

    /**
     * 按名字取计数（**把所有标签变体加在一起**）。
     *
     * <p>不能写 {@code find(name).counter()}：同一个名字一旦有第二个标签变体（比如冲突的
     * {@code reason=in_flight} 与 {@code reason=fingerprint_mismatch}），那个方法会返回 null，
     * 于是被当成 0——症状是「单跑绿、全量跑红」（其它测试类先把另一个变体创建出来了）。
     */
    private double counter(String name) {
        return registry.find(name).counters().stream().mapToDouble(Counter::count).sum();
    }

    @Test
    @DisplayName("审计写失败会打点，且不把异常抛给业务——最该配告警的那一个")
    void auditFailureIsCountedAndDoesNotBreakBusiness() {
        // 真的让审计写失败（mapper 抛异常），而不是只断言指标名字存在——
        // 只断言名字的那版是空断言：把打点整段删掉它照样绿
        AuditLogMapper failing = mock(AuditLogMapper.class);
        // 必须指明类型：BaseMapper 同时有 insert(T) 与 insert(Collection<T>)，
        // any() 会让编译器在两个重载之间报「both methods match」
        when(failing.insert(any(AuditLog.class))).thenThrow(new RuntimeException("库挂了"));
        AuditRecorder recorder = new AuditRecorder(failing, transactionManager, metrics);

        double before = counter(SecurityMetrics.AUDIT_WRITE_FAILED);

        recorder.recordAttempt(AuditLog.ACTION_LOGIN_FAILED, null, null, "hmac-for-test", "写不进去");

        assertThat(counter(SecurityMetrics.AUDIT_WRITE_FAILED))
                .as("审计失败必须可度量：它不阻断业务，所以没有任何面向用户的症状")
                .isEqualTo(before + 1);
    }

    @Test
    @DisplayName("幂等重放会打点（持续增长意味着客户端在重试风暴里）")
    void idempotentReplayIsCounted() {
        String token = api.registerAndGetAccessToken(PHONE);
        String key = "metrics-replay-key";

        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        headers.set("Idempotency-Key", key);
        String body = "{\"name\":\"指标测试\",\"species\":1}";

        double before = counter(SecurityMetrics.IDEMPOTENT_REPLAYED);

        for (int i = 0; i < 2; i++) {
            rest.exchange(rest.getRootUri() + "/api/v1/app/pets",
                    org.springframework.http.HttpMethod.POST,
                    new org.springframework.http.HttpEntity<>(body, headers), String.class);
        }

        assertThat(counter(SecurityMetrics.IDEMPOTENT_REPLAYED))
                .as("第二次是重放，计数必须 +1").isEqualTo(before + 1);
    }

    @Test
    @DisplayName("幂等冲突会打点，并带 reason 标签（在途 vs 同键不同体）")
    void idempotencyConflictIsCountedWithReason() {
        String token = api.registerAndGetAccessToken(PHONE);
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        headers.set("Idempotency-Key", "metrics-conflict-key");

        double before = counter(SecurityMetrics.IDEMPOTENCY_CONFLICT);

        rest.exchange(rest.getRootUri() + "/api/v1/app/pets",
                org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>("{\"name\":\"甲\",\"species\":1}", headers),
                String.class);
        // 同键不同体 → 40001
        rest.exchange(rest.getRootUri() + "/api/v1/app/pets",
                org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>("{\"name\":\"乙\",\"species\":1}", headers),
                String.class);

        assertThat(counter(SecurityMetrics.IDEMPOTENCY_CONFLICT)).isEqualTo(before + 1);
        assertThat(registry.find(SecurityMetrics.IDEMPOTENCY_CONFLICT)
                .tag("reason", "fingerprint_mismatch").counter())
                .as("标签要能区分冲突原因，否则排查时只知道「有冲突」")
                .isNotNull();
    }
}
