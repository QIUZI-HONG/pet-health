package com.pethealth.boot.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethealth.api.app.LoginRequest;
import com.pethealth.api.app.RegisterRequest;
import com.pethealth.boot.support.ApiClient;
import com.pethealth.boot.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.pethealth.account.domain.AuditLog;
import com.pethealth.account.service.AuditRecorder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 操作审计（ADR-0028）。
 *
 * <p>验的是三件事，按重要性排：
 *
 * <ol>
 *   <li><b>登录失败的审计行必须留下来</b>——业务事务注定回滚，审计若跟着它走，
 *       最该留证据的那一次反而什么都没留下。这是 {@code REQUIRES_NEW} 的存在理由，
 *       也是这个类里唯一一条「换个实现就会红」的断言。
 *   <li>四类动作各自落行，且带上操作者与 trace_id（写操作留 operator_id 与 trace_id 是硬约束）。
 *   <li><b>不落 PII 明文</b>：审计表的每一列都不该出现原始手机号。
 * </ol>
 */
@DisplayName("操作审计（ADR-0028）")
class AuditLogTest extends IntegrationTestBase {

    private static final String PHONE = "13900003001";
    private static final String PASSWORD = "Passw0rd123";

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuditRecorder recorder;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private ApiClient api;

    @BeforeEach
    void setUpClient() {
        api = new ApiClient(rest, objectMapper);
    }

    private List<Map<String, Object>> rows(String action) {
        return jdbc.queryForList("SELECT * FROM audit_log WHERE action = ? ORDER BY id", action);
    }

    @Test
    @DisplayName("注册与登录成功各落一行，带操作者与 trace_id")
    void registerAndLoginAreRecorded() {
        // 显式注册并带口令：不借 ApiClient.register 的默认口令，否则测试里那个 PASSWORD 常量
        // 与它不一致时，登录会**静默失败**，最后表现为「login_success 一行都没有」——
        // 排查方向会跑到审计上去，而真正的问题是口令不对
        ApiClient.ApiCall registered = api.post("/api/v1/app/auth/register",
                new RegisterRequest(PHONE, PASSWORD, null));
        assertThat(registered.code()).isZero();
        assertThat(registered.data().path("access_token").asText()).isNotBlank();

        List<Map<String, Object>> register = rows("register");
        assertThat(register).hasSize(1);
        assertThat(register.get(0).get("target_type")).isEqualTo("user");
        assertThat(((Number) register.get(0).get("created_by")).longValue())
                .as("注册者就是操作者").isPositive();
        assertThat(register.get(0).get("trace_id")).isEqualTo(registered.requestId());

        ApiClient.ApiCall loggedIn = api.post("/api/v1/app/auth/login", new LoginRequest(PHONE, PASSWORD));
        assertThat(loggedIn.code()).as("先确认登录真的成功了，再看审计").isZero();

        assertThat(rows("login_success")).hasSize(1);
    }

    @Test
    @DisplayName("登录失败的审计行**不会被业务事务回滚掉**")
    void failedLoginIsRecordedDespiteRollback() {
        api.post("/api/v1/app/auth/register", new RegisterRequest(PHONE, PASSWORD, null));

        ApiClient.ApiCall failed = api.post("/api/v1/app/auth/login",
                new LoginRequest(PHONE, "WrongPass123"));
        assertThat(failed.code()).as("密码错，业务上必然失败").isEqualTo(40100);

        List<Map<String, Object>> logs = rows("login_failed");
        assertThat(logs).as("业务失败了，审计必须留下").hasSize(1);
        assertThat(((Number) logs.get(0).get("created_by")).longValue())
                .as("未登录，操作者记 0").isZero();
        assertThat(logs.get(0).get("subject_ref").toString())
                .as("要能串出同一个手机号被撞了几次，所以主体引用不能为空").isNotBlank();
        assertThat(logs.get(0).get("trace_id")).isEqualTo(failed.requestId());
    }

    @Test
    @DisplayName("数据导出与账号注销各落一行（敏感数据访问）")
    void exportAndDeactivationAreRecorded() {
        String token = api.registerAndGetAccessToken(PHONE);

        assertThat(api.get("/api/v1/app/users/me/export", token).code()).isZero();
        assertThat(api.post("/api/v1/app/users/me/deactivation", null, token).code()).isZero();

        assertThat(rows("account_export")).hasSize(1);
        assertThat(rows("account_deactivate")).hasSize(1);
    }

    @Test
    @DisplayName("业务事务回滚：「事实」不落行，「尝试」照落行")
    void rollbackSuppressesOutcomeButNotAttempt() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.execute(status -> {
            // 事实类：注册成功、导出、注销都走这条。事务回滚了，这条记录就不该存在——
            // 审计里一条「说发生了、其实回滚了」的假记录，比缺记录更糟
            recorder.recordOutcome(AuditLog.ACTION_ACCOUNT_DEACTIVATE, 1L, 1L, "hmac-for-test", "会回滚");
            // 尝试类：登录失败走这条。它记的是「有人试过」，业务失败正是它要记的东西
            recorder.recordAttempt(AuditLog.ACTION_LOGIN_FAILED, null, null, "hmac-for-test", "会留下");
            status.setRollbackOnly();
            return null;
        });

        assertThat(rows("account_deactivate")).as("回滚了就不该有").isEmpty();
        assertThat(rows("login_failed")).as("被拒绝的尝试必须留下").hasSize(1);
    }

    @Test
    @DisplayName("审计行里不出现手机号明文（只留 HMAC 引用）")
    void noPlaintextPii() {
        api.post("/api/v1/app/auth/register", new RegisterRequest(PHONE, PASSWORD, null));
        api.post("/api/v1/app/auth/login", new LoginRequest(PHONE, "WrongPass123"));

        List<Map<String, Object>> all = jdbc.queryForList("SELECT * FROM audit_log");
        assertThat(all).isNotEmpty();
        for (Map<String, Object> row : all) {
            for (Map.Entry<String, Object> column : row.entrySet()) {
                assertThat(String.valueOf(column.getValue()))
                        .as("列 %s 里不该出现手机号明文", column.getKey())
                        .doesNotContain(PHONE);
            }
        }
    }
}
