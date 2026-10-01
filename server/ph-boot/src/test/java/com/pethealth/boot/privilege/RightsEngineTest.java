package com.pethealth.boot.privilege;

import com.pethealth.boot.support.ApiClient;
import com.pethealth.common.time.AppTime;
import com.pethealth.privilege.api.RightsApi;
import com.pethealth.privilege.service.RightsExpiryJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 权益引擎（切片 #112，决策见 ADR-0038 第三节 / ADR-0045）。
 *
 * <p>这一层盯的是**判定口径**，不是接口形状：
 *
 * <ul>
 *   <li><b>来源优先</b>：订阅 &gt; 邀请 &gt; 打卡，取生效里优先级最高的那一条，
 *       **不比较到期时间**；
 *   <li><b>到期只回收该来源那一条**：订阅到期后，邀请得的永久权益仍然生效；
 *   <li><b>每条来源各写一条**：同一码的两个来源是两条记录，不合并；
 *   <li><b>幂等</b>：同一来源 + 引用的重复授予只产生一条。
 * </ul>
 */
class RightsEngineTest extends PrivilegeTestSupport {

    @Autowired
    private RightsApi rightsApi;

    @Autowired
    private RightsExpiryJob expiryJob;

    /**
     * 请求体里的时间必须用**线上格式**（`yyyy-MM-dd HH:mm:ss`）显式格式化。
     *
     * <p>别用 `LocalDateTime.toString().replace('T', ' ')`：秒为 0 时 toString() 会省掉 `:00`
     * （变成 "2026-11-01 16:19"），后端按契约格式解析会在 index 16 处失败，回 40001
     * 「请求体格式不正确（不是合法的 JSON）」。于是用例只在「每分钟的第一秒」跑才红——
     * 2026-10-01 16:19 的一次全量 verify 实测踩到，同一份代码 30 秒后单跑就绿（时钟依赖假红）。
     *
     * <p>**取时间一律用 {@link AppTime#now()}（东八区），别用 {@code AppTime.now()}**——
     * 后者取 JVM 默认时区（CI 是 UTC），而服务端按东八区判「到期不能早于当前」：
     * `now().plusHours(1)` 在 UTC 的 JVM 里反而落在东八区「现在」的过去，grant 直接拒——
     * 2026-10-01 的 CI（Server 流水线）实测踩到，本地（JVM +08）则一直绿。
     */
    private static final DateTimeFormatter WIRE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 秒以下的精度对契约没有意义，统一截到秒 */
    private static String wireTime(LocalDateTime time) {
        return time.withNano(0).format(WIRE_TIME);
    }

    @Test
    @DisplayName("来源优先：邀请永久 + 打卡当月 → 取邀请；再加订阅月度 → 取订阅")
    void sourcePriorityBeatsExpiry() {
        long user = 810_001L;
        LocalDateTime monthEnd = AppTime.now().plusDays(20).withNano(0);

        // 打卡（当月）先授
        rightsApi.grant(new RightsApi.GrantCommand(user, "community.post", 3, "checkin:2026-09",
                monthEnd, null));
        assertThat(rightsApi.evaluate(user).get("community.post").source()).isEqualTo(3);

        // 邀请（永久）再授：优先级更高，判定要换成邀请那条——**不比较到期时间**
        rightsApi.grant(new RightsApi.GrantCommand(user, "community.post", 2, "invite:1", null, null));
        RightsApi.RightsState state = rightsApi.evaluate(user).get("community.post");
        assertThat(state.effective()).isTrue();
        assertThat(state.source()).isEqualTo(2);
        assertThat(state.expireAt()).isNull();

        // 订阅（月度，到期更近）优先级最高：即使它的到期时间比打卡那条早，也取它
        LocalDateTime subscriptionEnd = AppTime.now().plusDays(5).withNano(0);
        rightsApi.grant(new RightsApi.GrantCommand(user, "community.post", 1, "sub:888", subscriptionEnd, null));
        RightsApi.RightsState withSub = rightsApi.evaluate(user).get("community.post");
        assertThat(withSub.source()).isEqualTo(1);
        assertThat(withSub.expireAt()).isEqualTo(subscriptionEnd);

        // 订阅到期 → 只回收这一条，回落到邀请的永久权益
        jdbc.update("UPDATE rights_grant SET expire_at = ? WHERE user_id = ? AND source = 1",
                AppTime.now().minusMinutes(1), user);
        assertThat(rightsApi.revokeExpired()).isEqualTo(1);
        RightsApi.RightsState afterExpiry = rightsApi.evaluate(user).get("community.post");
        assertThat(afterExpiry.effective()).isTrue();
        assertThat(afterExpiry.source()).isEqualTo(2);

        // 三条记录都在，状态各自独立（订阅那条已回收，邀请与打卡仍生效）
        Map<String, Object> counts = jdbc.queryForMap("SELECT "
                + "SUM(status = 1) AS active, SUM(status = 2) AS revoked, COUNT(*) AS total "
                + "FROM rights_grant WHERE user_id = ?", user);
        assertThat(((Number) counts.get("active")).intValue()).isEqualTo(2);
        assertThat(((Number) counts.get("revoked")).intValue()).isEqualTo(1);
        assertThat(((Number) counts.get("total")).intValue()).isEqualTo(3);
    }

    @Test
    @DisplayName("判定是实时的：没有授予就不生效；过期未回收的授予也不算生效")
    void evaluationIsRealtime() {
        long user = 810_002L;
        assertThat(rightsApi.evaluate(user).get("ai.unlimited").effective()).isFalse();

        rightsApi.grant(new RightsApi.GrantCommand(user, "ai.unlimited", 3, "checkin:2026-09",
                AppTime.now().plusHours(1), null));
        assertThat(rightsApi.isEffective(user, "ai.unlimited")).isTrue();

        // 批算还没跑（状态仍是生效），但已过期的授予不该算生效——判定按时间实时算
        jdbc.update("UPDATE rights_grant SET expire_at = ? WHERE user_id = ?",
                AppTime.now().minusMinutes(1), user);
        assertThat(rightsApi.isEffective(user, "ai.unlimited")).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM rights_grant WHERE user_id = ?",
                Integer.class, user)).isEqualTo(1);

        // 判定结果里这个码仍然出现（带中文名）——运营看到的是「有码但不生效」，不是「什么都没有」
        ApiClient.ApiCall evaluation = api.get("/api/v1/admin/rights/users/" + user, adminToken());
        assertCodeOk(evaluation, "运营查判定");
        assertThat(evaluation.data().path("rights")).isNotEmpty();
        com.fasterxml.jackson.databind.JsonNode item = null;
        for (com.fasterxml.jackson.databind.JsonNode node : evaluation.data().path("rights")) {
            if ("ai.unlimited".equals(node.path("code").asText())) {
                item = node;
            }
        }
        assertThat(item).isNotNull();
        assertThat(item.path("effective").asBoolean()).isFalse();
        assertThat(item.path("name").asText()).isEqualTo("无限 AI 问答");
    }

    @Test
    @DisplayName("幂等：同一来源 + 引用的重复授予只产生一条；运营手动授予与回收走 HTTP")
    void grantIsIdempotentAndManualOpsWork() {
        String admin = adminToken();
        long user = 810_003L;

        // 运营手动授予（订阅：线下签约 + 后台标记，ADR-0036 之后没有支付载体）
        ApiClient.ApiCall granted = api.post("/api/v1/admin/rights/grants", Map.of(
                "user_id", user, "code", "report.full", "source", 1,
                "expire_at", wireTime(AppTime.now().plusMonths(1)),
                "source_ref", "sub-2026-09", "remark", "线下签约"), admin);
        assertCodeOk(granted, "手动授予");
        long grantId = granted.data().path("id").asLong();
        assertThat(granted.data().path("source").asInt()).isEqualTo(1);
        assertThat(granted.data().path("code_name").asText()).isEqualTo("完整报告");

        // 同一来源引用重复授予：**幂等**（不报错，也不多一条）
        assertCodeOk(api.post("/api/v1/admin/rights/grants", Map.of(
                "user_id", user, "code", "report.full", "source", 1,
                "source_ref", "sub-2026-09", "remark", "重复提交"), admin), "重复授予");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rights_grant WHERE user_id = ?",
                Long.class, user)).isEqualTo(1L);

        // 到期时间不能早于当前（授一条立刻过期的权益是调用方算错了）
        assertThat(api.post("/api/v1/admin/rights/grants", Map.of(
                "user_id", user, "code", "report.full", "source", 4,
                "expire_at", wireTime(AppTime.now().minusDays(1))),
                admin).code()).isEqualTo(40001);

        // 回收一条：只回收这一条，别的来源不受影响
        rightsApi.grant(new RightsApi.GrantCommand(user, "report.full", 2, "invite:9", null, null));
        assertCodeOk(api.delete("/api/v1/admin/rights/grants/" + grantId, admin), "回收订阅那条");
        var state = rightsApi.evaluate(user).get("report.full");
        assertThat(state.effective()).isTrue();
        assertThat(state.source()).isEqualTo(2);

        // 顶层资源：重复回收 / 不存在的都是 40400
        assertThat(api.delete("/api/v1/admin/rights/grants/" + grantId, admin).code()).isEqualTo(40400);
        assertThat(api.delete("/api/v1/admin/rights/grants/999999", admin).code()).isEqualTo(40400);
    }

    @Test
    @DisplayName("码表：运营可扩（格式受限、编码不可改、停用不撤销已有授予）")
    void codeTableIsExtensible() {
        String admin = adminToken();
        long user = 810_004L;

        assertCodeOk(api.post("/api/v1/admin/rights/codes", Map.of(
                "code", "quota.ai.plus", "name", "额外额度包", "description", "活动送的额度",
                "sort_order", 9), admin), "新增权益码");
        assertThat(api.post("/api/v1/admin/rights/codes", Map.of(
                "code", "quota.ai.plus", "name", "重复", "sort_order", 9), admin).code()).isEqualTo(40900);
        // 编码格式：小写字母 / 数字 / 点，且以字母开头（大写或中文一律 40001）
        assertThat(api.post("/api/v1/admin/rights/codes", Map.of(
                "code", "AI.Unlimited", "name", "大写", "sort_order", 9), admin).code()).isEqualTo(40001);

        // 停用只挡新授予：已有的授予照常生效
        rightsApi.grant(new RightsApi.GrantCommand(user, "quota.ai.plus", 4, "comp:1", null, "客诉补偿"));
        assertCodeOk(api.put("/api/v1/admin/rights/codes/quota.ai.plus",
                Map.of("name", "额外额度包", "status", 0), admin), "停用码");
        assertThat(rightsApi.isEffective(user, "quota.ai.plus")).isTrue();

        // 编码本身改不了
        assertThat(api.put("/api/v1/admin/rights/codes/quota.ai.plus",
                Map.of("code", "quota.ai.other", "name", "改名", "status", 1), admin).code())
                .isEqualTo(40001);

        // 不存在的码：授予与改码都 40400
        assertThat(api.post("/api/v1/admin/rights/grants", Map.of(
                "user_id", user, "code", "nope.nope", "source", 4), admin).code()).isEqualTo(40400);
        assertThat(api.put("/api/v1/admin/rights/codes/nope.nope",
                Map.of("name", "不存在"), admin).code()).isEqualTo(40400);
    }

    @Test
    @DisplayName("码表哨兵：care.mode 不是权益码（专项照护按医学事实自动开启，ADR-0032 / ADR-0038）")
    void careModeIsNotARightsCode() {
        ApiClient.ApiCall codes = api.get("/api/v1/admin/rights/codes", adminToken());
        assertThat(codes.data()).hasSize(4);
        assertThat(codes.data().findValuesAsText("code"))
                .containsExactlyInAnyOrder("ai.unlimited", "report.full", "community.post", "quota.ai.bonus")
                .doesNotContain("care.mode");
    }
}
