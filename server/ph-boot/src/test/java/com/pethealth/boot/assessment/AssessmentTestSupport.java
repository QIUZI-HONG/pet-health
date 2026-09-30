package com.pethealth.boot.assessment;

import com.fasterxml.jackson.databind.JsonNode;
import com.pethealth.account.auth.JwtService;
import com.pethealth.boot.provider.ProviderApiTestSupport;
import com.pethealth.common.security.LoginDomain;
import com.pethealth.common.time.AppTime;
import com.pethealth.provider.service.AssessmentMonthlyJob;
import com.pethealth.provider.service.AssessmentService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * 考核（F022）集成测试的公共基座。
 *
 * <p>三样东西：
 *
 * <ol>
 *   <li><b>清库与复位</b>：本切片的四张结果表 + 两张配置表（复位成种子值）+ 订单表
 *       （订单侧的口径由 {@code OrderStatsApi} 从 {@code order} 表算出来，所以测试直接造订单行——
 *       那正是「计数回 0、均值回 null」要被验证的地方）；父类负责服务者 / 咨询 / 账号那批表。
 *   <li><b>{@link ProviderGrowthFactsApi} 的测试实现</b>：它是 ph-api 里的跨模块只读接口，
 *       按契约应由 ph-privilege 提供实现（**本轮未接线**，见 ADR-0052 的「需要协调」）。
 *       这个 stub 就是那个实现的等价物——它读的还是同一批事实（有效邀请数、可贡献模板数、
 *       核销数与完成率），所以被这些用例验过的算分链路，在真实现落地后仍然成立。
 *       {@code AssessmentGrowthUnwiredTest} **刻意不导入它**，用来验证「数据源未接线 → 该维度不参与」
 *       这条降级口径（平台没给的数不记在服务者头上）。
 *   <li><b>造数工具</b>：造订单行、固定账期、取服务者 / 运营令牌。
 * </ol>
 *
 * <p>账期用**固定值** {@link #PERIOD}（2026-08）而不是「今天所在的上月」：算分是用例要验的东西，
 * 让日期随运行时间漂会让断言跟着漂。只有 {@code AssessmentMonthlyJobTest} 需要真日期
 * （它验的正是「每月 1 日算上月」），那里单独取。
 */
public abstract class AssessmentTestSupport extends ProviderApiTestSupport {

    /** 固定账期：造数与断言都对它，不随运行日期漂。 */
    protected static final YearMonth PERIOD = YearMonth.of(2026, 8);

    /** 超管名单里那个**固定 id 的账号**：常规用例用它调覆盖与规则配置（见下面的说明）。 */
    protected static final long SUPER_ADMIN_ID = 900001L;

    @Autowired
    protected AssessmentService assessmentService;

    @Autowired
    protected AssessmentMonthlyJob monthlyJob;

    @Autowired
    protected JwtService jwtService;

    @BeforeEach
    void cleanAssessmentData() {
        // 顺序：先子后主（逻辑引用，没有物理外键，但这个顺序读起来最清楚）
        jdbc.execute("DELETE FROM `assessment_override_log`");
        jdbc.execute("DELETE FROM `assessment_item_score`");
        jdbc.execute("DELETE FROM `assessment_monthly_score`");
        // 规则与档位是种子数据：复位，测试改过的要还回去
        jdbc.execute("UPDATE `assessment_rule` SET `invite_weight` = 40, `coupon_weight` = 40, "
                + "`process_weight` = 20, `invite_target` = 0, `coupon_target` = 0.00, "
                + "`response_minutes_target` = 30 WHERE `id` = 1");
        jdbc.execute("UPDATE `assessment_level_rule` SET `min_score` = CASE `level` "
                + "WHEN 1 THEN 0.00 WHEN 2 THEN 80.00 ELSE 90.00 END, "
                + "`recommend_priority` = CASE `level` WHEN 1 THEN 3 WHEN 2 THEN 2 ELSE 1 END");
        // 订单行是订单侧只读统计的来源，本切片自己造、自己清（父类不认识这张表）
        jdbc.execute("DELETE FROM `order`");
        // 固定 id 的超管账号：登录域校验会查账号状态（AccountStatus），所以它必须真在 user 表里
        createSuperAdminAccount();
    }

    // ---------------------------------------------------------------- 造数

    /**
     * 建那个**固定 id 的超管账号**（{@link #SUPER_ADMIN_ID}）。
     *
     * <p>为什么不用 {@code registerAccount()}（它会拿到一个自增 id）：超管名单是**环境变量**
     * （进程启动时读一次），而测试里的账号 id 是运行期才产生的——两者对不上。固定一个 id
     * 插进 user 表，就能让「@TestPropertySource 里配名单」与「测试里签令牌」对上同一个账号。
     *
     * <p>插入是幂等的（`INSERT IGNORE` 语义）：清库每个用例都会删掉 user 表的行。
     */
    protected void createSuperAdminAccount() {
        jdbc.update("INSERT INTO `user` (`id`, `phone_enc`, `phone_hash`, `password_hash`, `nickname`) "
                        + "VALUES (?, 'test-enc', 'test-hash-super-admin', 'x', '考核超管') "
                        + "ON DUPLICATE KEY UPDATE `is_deleted` = 0, `status` = 1",
                SUPER_ADMIN_ID);
    }

    /** 超管令牌（主体是那个固定 id 的账号；**只有在 {@code @TestPropertySource} 配了名单的上下文里才真能改**）。 */
    protected String superAdminToken() {
        return jwtService.issue(SUPER_ADMIN_ID, LoginDomain.ADMIN).token();
    }

    /** 账期内的第 n 天（造订单的预约日期用）。 */
    protected LocalDate day(int dayOfMonth) {
        return PERIOD.atDay(dayOfMonth);
    }

    /**
     * 造一条订单（只造订单侧统计要用的列）。
     *
     * @param status       0 待接单 / 1 已预约 / 2 履约中 / 3 已完成 / 4 已取消
     * @param cancelledBy  取消方（1 用户 / 2 服务者 / 3 运营）；不取消传 {@code null}
     * @param responseMins 接单响应分钟数（下单 → 接单）；传 {@code null} 表示**没有接单时刻**
     *                     （这正是「有单但一单未接」那种事实）
     */
    protected void seedOrder(long providerId, int status, Integer cancelledBy, Integer responseMins,
                             LocalDate appointmentDate) {
        LocalDateTime createdAt = appointmentDate.minusDays(1).atTime(10, 0);
        LocalDateTime acceptedAt = responseMins == null ? null : createdAt.plusMinutes(responseMins);
        String orderNo = "PH" + appointmentDate + "-" + System.nanoTime();
        // `created_at` 显式给：接单响应时长是「下单 → 接单」的差，而下单时刻由这条列定
        // （让它取库里的 CURRENT_TIMESTAMP 会得到「今天下单、去年接单」这种负时长）
        jdbc.update("INSERT INTO `order` (`order_no`, `user_id`, `pet_id`, `pet_name`, `pet_species`, "
                        + "`provider_id`, `service_id`, `service_code`, `service_name`, `appointment_date`, "
                        + "`start_time`, `end_time`, `total_amount`, `estimated_pay_amount`, `status`, "
                        + "`redeem_code`, `cancelled_by`, `accepted_at`, `created_at`) "
                        + "VALUES (?, 1, 1, '旺财', 1, ?, 1, 'GR-001', '洗护', ?, '10:00', '11:00', "
                        + "128.00, 128.00, ?, ?, ?, ?, ?)",
                orderNo, providerId, appointmentDate, status,
                String.format("%06d", Math.abs(orderNo.hashCode()) % 1_000_000),
                cancelledBy, acceptedAt, createdAt);
    }

    /** 把结果表里的四项配置改成测试要的样子（直接改库：配置接口的权限在别的用例里验）。 */
    protected void configureRule(Integer inviteTarget, String couponTarget, int responseMinutesTarget) {
        jdbc.update("UPDATE `assessment_rule` SET `invite_target` = ?, `coupon_target` = ?, "
                        + "`response_minutes_target` = ? WHERE `id` = 1",
                inviteTarget, couponTarget, responseMinutesTarget);
    }

    /** 改三项权重。 */
    protected void configureWeights(int inviteWeight, int couponWeight, int processWeight) {
        jdbc.update("UPDATE `assessment_rule` SET `invite_weight` = ?, `coupon_weight` = ?, "
                        + "`process_weight` = ? WHERE `id` = 1",
                inviteWeight, couponWeight, processWeight);
    }

    /** 某服务者某账期的总分（从库里读，不走接口）。 */
    protected BigDecimal totalScoreOf(long providerId, YearMonth period) {
        return jdbc.queryForObject("SELECT `total_score` FROM `assessment_monthly_score` "
                + "WHERE `provider_id` = ? AND `period` = ?", BigDecimal.class,
                providerId, period.toString());
    }

    /** 某个服务者在当前账期的考核分条数（幂等用例断言它一直是 1）。 */
    protected int scoreRowCount(long providerId, YearMonth period) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM `assessment_monthly_score` "
                + "WHERE `provider_id` = ? AND `period` = ?", Integer.class,
                providerId, period.toString());
    }

    /** 上月（{@code AssessmentMonthlyJob} 的口径：每月 1 日算上月）。 */
    protected static YearMonth lastMonth() {
        return YearMonth.from(AppTime.today()).minusMonths(1);
    }

    /** 从服务者侧的明细里取这条考核记录的 id（运营侧的路径参数就是它）。 */
    protected long scoreIdOf(String providerToken) {
        return api.get("/api/v1/provider/assessments/" + PERIOD, providerToken).data().path("id").asLong();
    }

    /** 服务者侧看某账期明细（失败时把整个响应打出来）。 */
    protected JsonNode detail(String providerToken, String period) {
        com.pethealth.boot.support.ApiClient.ApiCall call =
                api.get("/api/v1/provider/assessments/" + period, providerToken);
        assertCodeOk(call, "读考核明细");
        return call.data();
    }

    /** 一条合法的规则请求体（权重 40/40/20，三档阈值 0/80/90；达标线按参数给）。 */
    protected static Map<String, Object> ruleBody(String couponTarget) {
        return Map.of(
                "invite_weight", 40,
                "coupon_weight", 40,
                "process_weight", 20,
                "invite_target", 0,
                "coupon_target", couponTarget,
                "response_minutes_target", 30,
                "levels", List.of(
                        Map.of("level", 1, "min_score", "0.00", "recommend_priority", 3),
                        Map.of("level", 2, "min_score", "80.00", "recommend_priority", 2),
                        Map.of("level", 3, "min_score", "90.00", "recommend_priority", 1)));
    }

    /** 从明细里取某一项（不存在时直接把整个明细打出来，免得只看到一句「没有这一项」）。 */
    protected static JsonNode item(JsonNode detail, String itemCode) {
        for (JsonNode node : detail.path("items")) {
            if (itemCode.equals(node.path("item_code").asText())) {
                return node;
            }
        }
        throw new AssertionError("明细里没有这一项：" + itemCode + "，实际：" + detail.path("items"));
    }

}
