package com.pethealth.boot.privilege;

import com.pethealth.boot.support.ApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C 端增长链路四个出口（券 / 积分 / 权益 / 邀请，切片 #110–#113 的 C 端半边）。
 *
 * <p>盯三件事：**四个域的读形状与契约一致**、**写侧 /points/exchange 的规则仍在 ph-privilege 里**
 * （档位不存在 → 40400，不是本层自己判）、**后台令牌拿不到 C 端数据**（登录域把关）。
 */
@DisplayName("C 端增长链路：券 / 积分 / 权益 / 邀请（ADR-0037 / 0045 / 0046）")
class GrowthAppTest extends PrivilegeTestSupport {

    /** 注册一个 C 端账号，返回「用户 id + 它的访问令牌」（后台令牌拿不到这些数据，必须用 APP 域）。 */
    private record Actor(long userId, String token) {
    }

    private Actor registerAppUser() {
        ApiClient.ApiCall registered = api.register(nextPhone());
        assertCodeOk(registered, "注册 C 端账号");
        return new Actor(registered.data().path("user").path("id").asLong(),
                registered.data().path("access_token").asText());
    }

    @Test
    @DisplayName("我的券：只出自己的、按发放倒序；未登录 40100")
    void myCoupons() {
        String admin = adminToken();
        Actor mineActor = registerAppUser();
        Actor strangerActor = registerAppUser();
        long mine = mineActor.userId();
        long stranger = strangerActor.userId();
        ApiClient.ApiCall template = api.post("/api/v1/admin/coupon-templates", Map.of(
                "code", "CP-901", "name", "补贴券 CP-901", "face_value", "20.00", "min_amount", "0.00",
                "valid_days", 30, "cost_bearer", 2), admin);
        assertCodeOk(template, "建平台补贴券模板");
        long templateId = template.data().path("id").asLong();
        assertCodeOk(api.post("/api/v1/admin/coupons",
                Map.of("user_id", mine, "template_id", templateId), admin), "定向发券");
        assertCodeOk(api.post("/api/v1/admin/coupons",
                Map.of("user_id", stranger, "template_id", templateId), admin), "给别人发券");

        String token = mineActor.token();
        ApiClient.ApiCall coupons = api.get("/api/v1/app/coupons", token);
        assertCodeOk(coupons, "我的券");
        assertThat(coupons.data().path("list")).hasSize(1);
        assertThat(coupons.data().path("list").get(0).path("face_value").asText()).isEqualTo("20.00");
        assertThat(coupons.data().path("list").get(0).path("status").asInt()).isEqualTo(1);
        // 别人的券不可见
        assertThat(api.get("/api/v1/app/coupons", strangerActor.token()).data().path("list"))
                .as("券按用户隔离").hasSize(1);
        assertThat(api.get("/api/v1/app/coupons", null).code()).as("未登录").isEqualTo(40100);
    }

    @Test
    @DisplayName("积分中心与兑换：四块数据齐全；档位不存在 → 40400（规则仍在 ph-privilege 里）")
    void pointsCenterAndExchange() {
        Actor actor = registerAppUser();
        String token = actor.token();

        ApiClient.ApiCall center = api.get("/api/v1/app/points", token);
        assertCodeOk(center, "积分中心");
        assertThat(center.data().path("balance").asInt()).isZero();
        assertThat(center.data().path("daily_earn_limit").asInt()).isEqualTo(20);
        assertThat(center.data().path("tasks")).as("任务清单是种子数据").isNotEmpty();
        assertThat(center.data().path("behaviors")).as("行为分值表").isNotEmpty();
        assertThat(center.data().path("tasks").get(0).path("behavior_code").asText()).isNotBlank();

        ApiClient.ApiCall badOption = api.post("/api/v1/app/points/exchange",
                Map.of("option_id", 999_999), token);
        assertThat(badOption.code()).as("档位不存在：规则在 PointsApi 里，不由本层自己造").isEqualTo(40400);
        ApiClient.ApiCall noOption = api.post("/api/v1/app/points/exchange", Map.of(), token);
        assertThat(noOption.code()).as("option_id 必填").isEqualTo(40001);
    }

    @Test
    @DisplayName("我的权益：四个种子码全列出（不生效的也列），未登录 40100")
    void rights() {
        String token = registerAppUser().token();
        ApiClient.ApiCall rights = api.get("/api/v1/app/rights", token);
        assertCodeOk(rights, "我的权益");
        assertThat(rights.data().path("rights")).hasSize(4);
        assertThat(rights.data().path("rights").get(0).path("code").asText()).isNotBlank();
        assertThat(rights.data().path("rights").get(0).path("name").asText()).isNotBlank();
        assertThat(api.get("/api/v1/app/rights", null).code()).isEqualTo(40100);
    }

    @Test
    @DisplayName("邀请：生成一人一码（渠道只记首次）、中心页四类计数与五档阶梯")
    void invites() {
        String token = registerAppUser().token();

        ApiClient.ApiCall centerBefore = api.get("/api/v1/app/invites/code", token);
        assertCodeOk(centerBefore, "邀请中心");
        assertThat(centerBefore.data().path("codes")).as("还没生成过：空数组引导去生成").isEmpty();
        assertThat(centerBefore.data().path("ladder")).as("五档阶梯").hasSize(5);
        assertThat(centerBefore.data().path("next_threshold").asInt())
                .as("第一档门槛 1").isEqualTo(1);

        ApiClient.ApiCall created = api.post("/api/v1/app/invites/code", Map.of("channel", 1), token);
        assertCodeOk(created, "生成邀请码");
        String code = created.data().path("code").asText();
        assertThat(code).isNotBlank();
        assertThat(created.data().path("channel").asInt()).isEqualTo(1);
        assertThat(created.data().path("invite_path").asText()).isEqualTo("/register?invite=" + code);

        ApiClient.ApiCall again = api.post("/api/v1/app/invites/code", Map.of("channel", 2), token);
        assertCodeOk(again, "重复生成");
        assertThat(again.data().path("code").asText()).as("一人一码").isEqualTo(code);
        assertThat(again.data().path("channel").asInt())
                .as("渠道只记首次生成时的那一个（ADR-0049 §八）").isEqualTo(1);

        ApiClient.ApiCall center = api.get("/api/v1/app/invites/code", token);
        assertThat(center.data().path("codes")).hasSize(1);
        assertThat(center.data().path("codes").get(0).path("code").asText()).isEqualTo(code);
        assertThat(center.data().path("registered_count").asInt()).isZero();
        assertThat(center.data().path("effective_count").asInt()).isZero();
        assertThat(center.data().path("next_remaining").asInt()).isEqualTo(1);
        assertThat(center.data().path("ladder").get(0).path("achieved").asBoolean()).isFalse();
        assertThat(center.data().path("ladder").get(0).path("reward_desc").isNull())
                .as("该档没配奖励时为空（达成照记）").isTrue();
    }

    @Test
    @DisplayName("券包页的锁 / 释放：锁定幂等、释放幂等；被订单持有 → 40900；别人的券 40400")
    void couponLockAndRelease() {
        String admin = adminToken();
        Actor owner = registerAppUser();
        Actor stranger = registerAppUser();
        ApiClient.ApiCall template = api.post("/api/v1/admin/coupon-templates", Map.of(
                "code", "CP-902", "name", "补贴券 CP-902", "face_value", "20.00", "min_amount", "0.00",
                "valid_days", 30, "cost_bearer", 2), admin);
        assertCodeOk(template, "建平台补贴券模板");
        ApiClient.ApiCall issued = api.post("/api/v1/admin/coupons", Map.of(
                "user_id", owner.userId(), "template_id", template.data().path("id").asLong()), admin);
        assertCodeOk(issued, "定向发券");
        long couponId = issued.data().path("id").asLong();

        // 别人的券：按不存在处理
        assertThat(api.post("/api/v1/app/coupons/" + couponId + "/lock", null, stranger.token()).code())
                .isEqualTo(40400);

        // 锁定 + 幂等
        ApiClient.ApiCall locked = api.post("/api/v1/app/coupons/" + couponId + "/lock", null, owner.token());
        assertCodeOk(locked, "锁定");
        assertThat(locked.data().path("status").asInt()).isEqualTo(2);
        assertCodeOk(api.post("/api/v1/app/coupons/" + couponId + "/lock", null, owner.token()), "重复锁定幂等");

        // 释放 + 幂等（未锁定再释放也是成功）
        ApiClient.ApiCall released = api.post("/api/v1/app/coupons/" + couponId + "/release", null, owner.token());
        assertCodeOk(released, "释放");
        assertThat(released.data().path("status").asInt()).isEqualTo(1);
        assertCodeOk(api.post("/api/v1/app/coupons/" + couponId + "/release", null, owner.token()), "重复释放幂等");

        // 被一个**未结束的订单**持有：单独释放要 40900（先取消那一单）。
        // 用一条 SQL 把持有者改成某个真实订单 id（等价于「下单时锁上了」这个状态），
        // 免得为了造这个状态把订单链路整条拉进这个测试类——订单侧的锁由 OrderCreateTest 覆盖。
        jdbc.update("UPDATE `coupon` SET `status` = 2, `locked_order_id` = 7 WHERE `id` = ?", couponId);
        ApiClient.ApiCall held = api.post("/api/v1/app/coupons/" + couponId + "/release", null, owner.token());
        assertThat(held.code()).as("券被未结束的订单占用").isEqualTo(40900);
        assertThat(api.post("/api/v1/app/coupons/" + couponId + "/lock", null, owner.token()).code())
                .as("已被订单占用：80001").isEqualTo(80001);
    }

    @Test
    @DisplayName("每日签到：记一次 SIGN_IN 行为并加运营配的分；同一天再签是 200 + awarded=false，不是错误")
    void signInIsIdempotentPerDay() {
        Actor owner = registerAppUser();
        int before = api.get("/api/v1/app/points", owner.token()).data().path("balance").asInt();

        ApiClient.ApiCall first = api.post("/api/v1/app/points/sign-in", null, owner.token());
        assertCodeOk(first, "签到");
        assertThat(first.data().path("awarded").asBoolean()).isTrue();
        // 分值取自 point_behavior 的当前值（种子是 1 分）：运营在后台改分值不该让这条用例变成假红
        int signInPoints = jdbc.queryForObject(
                "SELECT `points` FROM `point_behavior` WHERE `code` = 'SIGN_IN'", Integer.class);
        assertThat(first.data().path("points").asInt()).isEqualTo(signInPoints);
        assertThat(first.data().path("balance").asInt()).isEqualTo(before + signInPoints);
        assertThat(first.data().path("notice").asText()).isNotBlank();

        // 同一天再签：**结果不是错误**（契约写明了），余额与流水都不动
        ApiClient.ApiCall again = api.post("/api/v1/app/points/sign-in", null, owner.token());
        assertCodeOk(again, "重复签到仍是成功");
        assertThat(again.data().path("awarded").asBoolean()).isFalse();
        assertThat(again.data().path("points").asInt()).isZero();
        assertThat(again.data().path("balance").asInt()).isEqualTo(before + signInPoints);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM `point_record` WHERE `user_id` = ? AND `behavior_code` = 'SIGN_IN'",
                Integer.class, owner.userId())).as("一天只留一条签到流水").isEqualTo(1);

        // 任务进度按流水聚合：签到那条任务的 completed 因此翻成 true（不是另记一个状态）
        for (var task : api.get("/api/v1/app/points", owner.token()).data().path("tasks")) {
            if ("DAILY_SIGN_IN".equals(task.path("code").asText())) {
                assertThat(task.path("completed").asBoolean()).as("签到后该任务应显示完成").isTrue();
            }
        }

        assertThat(api.post("/api/v1/app/points/sign-in", null, null).code()).as("未登录").isEqualTo(40100);
    }
}
