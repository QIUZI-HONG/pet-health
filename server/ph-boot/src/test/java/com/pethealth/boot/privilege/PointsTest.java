package com.pethealth.boot.privilege;

import com.fasterxml.jackson.databind.JsonNode;
import com.pethealth.boot.support.ApiClient;
import com.pethealth.common.error.BusinessException;
import com.pethealth.privilege.api.CouponApi;
import com.pethealth.privilege.api.PointsApi;
import com.pethealth.privilege.service.MonthlyLadderJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 积分与任务（切片 #113，决策见 ADR-0038 第四节 / ADR-0046）。
 *
 * <p>这一层盯五条口径：
 *
 * <ul>
 *   <li><b>行为发分</b>：分值取自行为表（签到 1 / 打卡 3 / 邀请 20 / 评价 5 / 完善档案 10）；
 *   <li><b>幂等</b>：同一行为 + 同一来源引用只发一次（重放不发第二笔）；
 *   <li><b>每日上限 20 分</b>：**邀请与一次性项不占这个上限**（否则一次邀请就把当天吃光）；
 *   <li><b>频次约束</b>：每日 1 次（签到 / 打卡）、每月 5 次（评价）、一次性（完善档案）；
 *   <li><b>兑换只兑平台补贴券</b>：消耗平台的钱，不消耗服务者的贡献额度。
 * </ul>
 */
class PointsTest extends PrivilegeTestSupport {

    @Autowired
    private PointsApi pointsApi;

    @Autowired
    private CouponApi couponApi;

    @Autowired
    private MonthlyLadderJob ladderJob;

    @Test
    @DisplayName("发分：分值、幂等、每日次数、一次性、流水带着变动后余额")
    void awardRules() {
        long user = 830_001L;

        assertThat(pointsApi.award(command(user, "SIGN_IN", "2026-09-30")).awarded()).isTrue();
        assertThat(pointsApi.balanceOf(user)).isEqualTo(1);
        // 同一引用重复：不发第二笔
        var replay = pointsApi.award(command(user, "SIGN_IN", "2026-09-30"));
        assertThat(replay.awarded()).isFalse();
        assertThat(replay.reason()).isEqualTo("DUPLICATE");
        assertThat(pointsApi.balanceOf(user)).isEqualTo(1);
        // 每日 1 次：换个引用也不行
        assertThat(pointsApi.award(command(user, "SIGN_IN", "again")).reason()).isEqualTo("DAILY_COUNT_LIMIT");

        assertThat(pointsApi.award(command(user, "CHECK_IN", "pet-1:2026-09-30")).points()).isEqualTo(3);
        assertThat(pointsApi.balanceOf(user)).isEqualTo(4);
        // 今日已获得（占上限的部分）：1 + 3 = 4
        assertThat(pointsApi.todayEarned(user)).isEqualTo(4);

        // 一次性：完善档案只能拿一次
        assertThat(pointsApi.award(command(user, "PROFILE_COMPLETE", "once-1")).points()).isEqualTo(10);
        assertThat(pointsApi.award(command(user, "PROFILE_COMPLETE", "once-2")).reason())
                .isEqualTo("ONCE_ONLY");
        // 它不占每日上限
        assertThat(pointsApi.todayEarned(user)).isEqualTo(4);
        assertThat(pointsApi.balanceOf(user)).isEqualTo(14);

        // 流水：每条都带变动后余额，能重建任意时刻的余额
        List<Map<String, Object>> records = jdbc.queryForList(
                "SELECT behavior_code, change_amount, balance_after FROM point_record WHERE user_id = ? ORDER BY id",
                user);
        assertThat(records).hasSize(3);
        assertThat(((Number) records.get(0).get("balance_after")).intValue()).isEqualTo(1);
        assertThat(((Number) records.get(1).get("balance_after")).intValue()).isEqualTo(4);
        assertThat(((Number) records.get(2).get("balance_after")).intValue()).isEqualTo(14);

        // 未启用的行为不发分；未知行为码也不发（都不抛异常——发分失败不该让打卡失败）
        jdbc.update("UPDATE point_behavior SET status = 0 WHERE code = 'REVIEW'");
        assertThat(pointsApi.award(command(user, "REVIEW", "order-1")).reason()).isEqualTo("BEHAVIOR_DISABLED");
        assertThat(pointsApi.award(command(user, "NOT_A_BEHAVIOR", "x")).reason()).isEqualTo("BEHAVIOR_UNKNOWN");

        // 运营能改分值（业务可调项），改完立刻生效
        assertCodeOk(api.put("/api/v1/admin/points/behaviors/CHECK_IN",
                Map.of("points", 5), adminToken()), "改打卡分值");
        assertThat(pointsApi.award(command(830_002L, "CHECK_IN", "pet-2:2026-09-30")).points()).isEqualTo(5);
        // 行为码不能新增（新增行为是代码变更）——所以「8 行」这个数是代码与迁移的契约：
        // 7 条 V27 的种子里加 1 条 V38 的 INVITE_INVITEE（被邀请人奖励，默认 0 分 = 不发）
        var behaviors = api.get("/api/v1/admin/points/behaviors", adminToken()).data();
        assertThat(behaviors).hasSize(8);
        Integer inviteeRewardPoints = null;
        for (var node : behaviors) {
            if ("INVITE_INVITEE".equals(node.path("code").asText())) {
                inviteeRewardPoints = node.path("points").asInt();
            }
        }
        assertThat(inviteeRewardPoints)
                .as("默认 0 分：0 = 记行为不发分，运营定值后才真的发（ADR-0046 第五节）")
                .isNotNull()
                .isZero();
    }

    @Test
    @DisplayName("每日上限 20 分：占上限的行为到顶就不发，邀请与一次性项不受影响")
    void dailyEarnLimit() {
        long user = 830_010L;
        // 打卡 3 分 × 6 = 18 分（每次换一个宠物引用，避开每日 1 次的次数限制用 REVIEW 更合适，
        // 这里直接用 REVIEW 的 5 分档：4 次 = 20 分刚好到顶）
        assertThat(pointsApi.award(command(user, "REVIEW", "order-1")).points()).isEqualTo(5);
        assertThat(pointsApi.award(command(user, "REVIEW", "order-2")).points()).isEqualTo(5);
        assertThat(pointsApi.award(command(user, "REVIEW", "order-3")).points()).isEqualTo(5);
        assertThat(pointsApi.award(command(user, "REVIEW", "order-4")).points()).isEqualTo(5);
        assertThat(pointsApi.todayEarned(user)).isEqualTo(20);

        // 到顶：打卡（占上限）不再发分
        var capped = pointsApi.award(command(user, "CHECK_IN", "pet:2026-09-30"));
        assertThat(capped.awarded()).isFalse();
        assertThat(capped.reason()).isEqualTo("DAILY_LIMIT");
        assertThat(capped.balanceAfter()).isEqualTo(20);

        // 但邀请（20 分，不占上限）照发——否则一次邀请就把当天的上限吃光
        var invite = pointsApi.award(command(user, "INVITE", "invite:1"));
        assertThat(invite.awarded()).isTrue();
        assertThat(invite.points()).isEqualTo(20);
        assertThat(pointsApi.balanceOf(user)).isEqualTo(40);
        assertThat(pointsApi.todayEarned(user)).isEqualTo(20);

        // 运营把上限调大后立刻生效（可调项，进库 + 后台）
        assertCodeOk(api.put("/api/v1/admin/points/settings", Map.of("daily_earn_limit", 40), adminToken()),
                "改每日上限");
        assertThat(pointsApi.award(command(user, "CHECK_IN", "pet:2026-09-30")).awarded()).isTrue();
        ApiClient.ApiCall overview = api.get("/api/v1/admin/points/overview", adminToken());
        assertThat(overview.data().path("daily_earn_limit").asInt()).isEqualTo(40);
        assertThat(overview.data().path("today_earned").asInt()).isEqualTo(43);
    }

    @Test
    @DisplayName("兑换：只兑平台补贴券，扣分与发券同一个事务；积分不足给 40900")
    void exchangeToSubsidyCoupon() {
        String admin = adminToken();
        long subsidyTemplateId = api.post("/api/v1/admin/coupon-templates", Map.of(
                        "code", "CP-301", "name", "30 元体检券", "face_value", "30.00", "min_amount", "100.00",
                        "valid_days", 30, "cost_bearer", 2), admin)
                .data().path("id").asLong();
        // 服务者成本的券不能配成兑换档位（那等于把兑换成本转嫁给服务者）
        long providerCostTemplateId = api.post("/api/v1/admin/coupon-templates", Map.of(
                        "code", "CP-302", "name", "服务者出的券", "face_value", "20.00", "min_amount", "0.00",
                        "valid_days", 30, "cost_bearer", 1), admin)
                .data().path("id").asLong();
        assertThat(api.post("/api/v1/admin/points/exchange-options", Map.of(
                "name", "非法档位", "points_cost", 30, "coupon_template_id", providerCostTemplateId), admin)
                .code()).isEqualTo(40900);

        ApiClient.ApiCall option = api.post("/api/v1/admin/points/exchange-options", Map.of(
                "name", "30 元体检券", "points_cost", 30, "coupon_template_id", subsidyTemplateId,
                "sort_order", 1), admin);
        assertCodeOk(option, "建兑换档位");
        long optionId = option.data().path("id").asLong();
        assertThat(option.data().path("coupon_face_value").asText()).isEqualTo("30.00");

        long user = 830_020L;
        // 积分不足
        var insufficient = catchThrowable(() -> pointsApi.exchange(user, optionId));
        assertThat(insufficient).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) insufficient).getErrorCode().getCode())
                .isEqualTo(40900);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon", Long.class)).isZero();

        // 攒够分再兑：扣分 30、发一张券
        pointsApi.award(command(user, "INVITE", "invite:e1")); // 20
        pointsApi.award(command(user, "PROFILE_COMPLETE", "profile:e1")); // 10
        var exchanged = pointsApi.exchange(user, optionId);
        assertThat(exchanged.pointsCost()).isEqualTo(30);
        assertThat(exchanged.balanceAfter()).isZero();
        assertThat(exchanged.couponCode()).isNotBlank();

        Map<String, Object> couponRow = jdbc.queryForMap("SELECT source, user_id, face_value, template_id "
                + "FROM coupon WHERE id = ?", exchanged.couponId());
        assertThat(((Number) couponRow.get("source")).intValue()).isEqualTo(3);
        assertThat(((Number) couponRow.get("user_id")).longValue()).isEqualTo(user);
        assertThat(((Number) couponRow.get("template_id")).longValue()).isEqualTo(subsidyTemplateId);

        // 流水：扣分那条带变动后余额 0，并且不占每日上限
        Map<String, Object> spendRow = jdbc.queryForMap("SELECT change_amount, balance_after, "
                + "counts_toward_daily_cap FROM point_record WHERE user_id = ? AND change_amount < 0", user);
        assertThat(((Number) spendRow.get("change_amount")).intValue()).isEqualTo(-30);
        assertThat(((Number) spendRow.get("balance_after")).intValue()).isZero();
        assertThat(((Number) spendRow.get("counts_toward_daily_cap")).intValue()).isZero();

        // 停用的档位不能兑
        assertCodeOk(api.put("/api/v1/admin/points/exchange-options/" + optionId, Map.of(
                "name", "30 元体检券", "points_cost", 30, "coupon_template_id", subsidyTemplateId,
                "status", 0), admin), "停用档位");
        var disabled = catchThrowable(() -> pointsApi.exchange(user, optionId));
        assertThat(disabled).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) disabled).getErrorCode().getCode())
                .isEqualTo(40400);
    }

    @Test
    @DisplayName("任务清单：入库存配置（每日 + 每周两档），任务引用的行为必须已存在")
    void taskCatalogue() {
        String admin = adminToken();
        ApiClient.ApiCall tasks = api.get("/api/v1/admin/points/tasks", admin);
        assertThat(tasks.data()).hasSize(6);
        assertThat(tasks.data().get(0).path("period").asInt()).isEqualTo(1);
        assertThat(tasks.data().get(0).path("code").asText()).isEqualTo("DAILY_SIGN_IN");
        assertThat(tasks.data().get(0).path("points").asInt()).isEqualTo(1);
        assertThat(tasks.data().get(4).path("period").asInt()).isEqualTo(2);
        assertThat(tasks.data().get(4).path("target_count").asInt()).isEqualTo(5);

        // 新增任务：必须引用已有行为；同一档位同一行为只能有一个任务
        assertCodeOk(api.post("/api/v1/admin/points/tasks", Map.of(
                "code", "DAILY_REVIEW", "name", "写评价", "period", 1, "behavior_code", "REVIEW",
                "target_count", 1, "sort_order", 5), admin), "新增任务");
        assertThat(api.post("/api/v1/admin/points/tasks", Map.of(
                "code", "DAILY_REVIEW_2", "name", "重复", "period", 1, "behavior_code", "REVIEW",
                "target_count", 1), admin).code()).isEqualTo(40900);
        assertThat(api.post("/api/v1/admin/points/tasks", Map.of(
                "code", "DAILY_NOPE", "name", "不存在的行为", "period", 1, "behavior_code", "NOPE",
                "target_count", 1), admin).code()).isEqualTo(40001);
        // 任务编码不可改
        long taskId = jdbc.queryForObject("SELECT id FROM point_task WHERE code = 'DAILY_REVIEW'", Long.class);
        assertThat(api.put("/api/v1/admin/points/tasks/" + taskId, Map.of(
                "code", "DAILY_REVIEW_X", "name", "改名", "period", 1, "behavior_code", "REVIEW",
                "target_count", 1), admin).code()).isEqualTo(40001);
    }

    @Test
    @DisplayName("月度阶梯骨架：没配档位不发东西；配了档位按上月累计发券，且一个账期只发一次")
    void monthlyLadderSkeleton() {
        String admin = adminToken();
        long user = 830_030L;
        LocalDate lastMonth = LocalDate.now().minusMonths(1).withDayOfMonth(15);

        // 上月累计 60 分（直接写流水，模拟上月的行为）
        jdbc.update("INSERT INTO point_record (user_id, behavior_code, change_amount, balance_after, "
                        + "counts_toward_daily_cap, business_date, source_ref) VALUES (?, 'INVITE', 60, 60, 0, ?, 'history')",
                user, lastMonth);

        // 1) 没配档位：骨架照跑，但不发券
        var empty = ladderJob.run();
        assertThat(empty.period()).isEqualTo(YearMonth.from(lastMonth).toString());
        assertThat(empty.scanned()).isEqualTo(1);
        assertThat(empty.granted()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon", Long.class)).isZero();

        // 2) 配上档位（门槛 50 → 发 1 张平台补贴券）
        long subsidyTemplateId = api.post("/api/v1/admin/coupon-templates", Map.of(
                        "code", "CP-401", "name", "月度阶梯券", "face_value", "15.00", "min_amount", "0.00",
                        "valid_days", 30, "cost_bearer", 2), admin)
                .data().path("id").asLong();
        assertCodeOk(api.post("/api/v1/admin/points/ladder-tiers", Map.of(
                "threshold_points", 50, "coupon_template_id", subsidyTemplateId, "coupon_count", 1,
                "sort_order", 1), admin), "配阶梯档位");
        // 门槛重复 → 409
        assertThat(api.post("/api/v1/admin/points/ladder-tiers", Map.of(
                "threshold_points", 50, "coupon_template_id", subsidyTemplateId, "coupon_count", 1), admin)
                .code()).isEqualTo(40900);

        var granted = ladderJob.run();
        assertThat(granted.granted()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT source FROM coupon", Integer.class)).isEqualTo(5);
        Map<String, Object> grantRow = jdbc.queryForMap("SELECT period, threshold_points, "
                + "cumulative_points FROM point_ladder_grant WHERE user_id = ?", user);
        assertThat(grantRow.get("period")).asString().isEqualTo(empty.period());
        assertThat(((Number) grantRow.get("threshold_points")).intValue()).isEqualTo(50);
        assertThat(((Number) grantRow.get("cumulative_points")).intValue()).isEqualTo(60);

        // 重跑不再发（(user_id, period) 唯一）
        assertThat(ladderJob.run().granted()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon", Long.class)).isEqualTo(1L);
    }

    @Test
    @DisplayName("邀请归因与积分衔接：有效邀请的 20 分由积分侧发出，且不占每日上限")
    void inviteAwardIsDelegatedToPoints() {
        // 这条链路在 InviteTest 里已完整覆盖，这里只钉住「积分侧认这个行为码」这一点，
        // 避免有人把 INVITE 的 counts_toward_daily_cap 改成 1（那会让大额奖励被日上限吃掉）
        ApiClient.ApiCall behaviors = api.get("/api/v1/admin/points/behaviors", adminToken());
        JsonNode invite = null;
        for (JsonNode node : behaviors.data()) {
            if ("INVITE".equals(node.path("code").asText())) {
                invite = node;
            }
        }
        assertThat(invite).isNotNull();
        assertThat(invite.path("points").asInt()).isEqualTo(20);
        assertThat(invite.path("counts_toward_daily_cap").asInt()).isZero();
    }

    private PointsApi.AwardCommand command(long userId, String behavior,
                                                                       String sourceRef) {
        return new PointsApi.AwardCommand(userId, behavior, sourceRef, null);
    }
}
