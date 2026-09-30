package com.pethealth.boot.record;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethealth.api.app.CheckInItemRequest;
import com.pethealth.api.app.CheckInSubmitRequest;
import com.pethealth.boot.support.ApiClient;
import com.pethealth.boot.support.IntegrationTestBase;
import com.pethealth.common.time.AppTime;
import com.pethealth.privilege.api.PointsApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 打卡 → 行为发分的接线验证（ADR-0046「需要协调」第 3 条）。
 *
 * <p>断言的全是**流水与余额**（外部可观察状态），不看有没有调用过谁：
 *
 * <ul>
 *   <li>打卡成功 → {@code point_record} 恰好 +1 条、余额 +3（`CHECK_IN` 的分值是种子里定的 3）；
 *   <li>同一天再打卡（换一项、甚至换一只宠物）→ **不重复发分**（幂等引用 + 每日 1 次）；
 *   <li>发分没发成（行为被运营停用）→ **打卡照常成功**：分是另一套账，丢了可以再补，
 *       打卡记录丢了补不回来（ADR-0046 的口径）。
 * </ul>
 */
class CheckInPointsWiringTest extends IntegrationTestBase {

    private ApiClient api;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PointsApi pointsApi;

    @BeforeEach
    void setUpPoints() {
        api = new ApiClient(rest, objectMapper);
        // 积分那两张表不在 IntegrationTestBase 的清理名单里（跨切片共享的基类），本切片自己清。
        // 种子（point_behavior / point_config）不动，只把用例改过的启停复位
        jdbc.execute("DELETE FROM `point_record`");
        jdbc.execute("DELETE FROM `user_point`");
        jdbc.execute("UPDATE `point_behavior` SET `status` = 1, `points` = 3 WHERE `code` = 'CHECK_IN'");
    }

    @Test
    @DisplayName("打卡成功 → 积分流水 +1 条、余额 +3（业务日引用 checkin:yyyy-MM-dd）")
    void checkInAwardsPoints() {
        String token = register("13710000001");
        long userId = userIdOf(token);
        long petId = api.createPet(token, "豆豆");

        ApiClient.ApiCall day = submit(token, petId, today(), item(2, false, "normal", null));
        assertCodeOk(day, "打卡");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `point_record` WHERE `user_id` = ?",
                Integer.class, userId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT `change_amount` FROM `point_record` WHERE `user_id` = ?",
                Integer.class, userId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT `behavior_code` FROM `point_record` WHERE `user_id` = ?",
                String.class, userId)).isEqualTo("CHECK_IN");
        assertThat(jdbc.queryForObject("SELECT `source_ref` FROM `point_record` WHERE `user_id` = ?",
                String.class, userId)).isEqualTo("checkin:" + today());
        assertThat(pointsApi.balanceOf(userId)).isEqualTo(3);
        assertThat(pointsApi.todayEarned(userId)).isEqualTo(3);
    }

    @Test
    @DisplayName("同一天重复打卡（换分项、换宠物）不重复发分")
    void sameDayRepeatDoesNotAwardAgain() {
        String token = register("13710000002");
        long userId = userIdOf(token);
        long petId = api.createPet(token, "豆豆");
        long secondPet = api.createPet(token, "毛毛");

        assertCodeOk(submit(token, petId, today(), item(2, false, "normal", null)), "第一次打卡");
        // 换一项：打卡本身是**新记录**（不是重复提交），但今天的分已经发过了
        assertCodeOk(submit(token, petId, today(), item(3, false, "normal", null)), "第二次打卡（换项）");
        // 换一只宠物：分是按用户算的，一天一次（sourceRef 只带业务日，不带宠物）
        assertCodeOk(submit(token, secondPet, today(), item(2, false, "normal", null)), "第三只宠物打卡");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `point_record` WHERE `user_id` = ?",
                Integer.class, userId)).isEqualTo(1);
        assertThat(pointsApi.balanceOf(userId)).isEqualTo(3);
        // 打卡记录本身照常落了三条（打卡幂等的是「同一宠物 + 同一分项」，与发分无关）
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `archive_record` WHERE `user_id` = ? "
                + "AND `record_date` = ? AND `is_deleted` = 0", Integer.class, userId, today())).isEqualTo(3);
    }

    @Test
    @DisplayName("发分没发成（行为已停用）也不拖垮打卡：接口成功、记录落库、只是没有分")
    void awardFailureDoesNotBreakCheckIn() {
        String token = register("13710000003");
        long userId = userIdOf(token);
        long petId = api.createPet(token, "豆豆");
        // 运营把「打卡」这个行为停用：award 会返回 awarded=false（BEHAVIOR_DISABLED），不是异常
        jdbc.execute("UPDATE `point_behavior` SET `status` = 0 WHERE `code` = 'CHECK_IN'");

        ApiClient.ApiCall day = submit(token, petId, today(), item(2, true, "low", "吃得很少"));

        assertCodeOk(day, "打卡（发分失败时仍必须成功）");
        assertThat(day.data().path("done").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `archive_record` WHERE `pet_id` = ? "
                + "AND `is_deleted` = 0", Integer.class, petId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `point_record` WHERE `user_id` = ?",
                Integer.class, userId)).isZero();
        assertThat(pointsApi.balanceOf(userId)).isZero();
    }

    // ---------------------------------------------------------------- 工具

    private String register(String phone) {
        return api.registerAndGetAccessToken(phone);
    }

    /** 登录令牌对应的用户 id（从 /users/me 读，不猜）。 */
    private long userIdOf(String token) {
        return api.get("/api/v1/app/users/me", token).data().path("id").asLong();
    }

    private LocalDate today() {
        return AppTime.today();
    }

    private CheckInItemRequest item(int category, boolean abnormal, String value, String note) {
        return new CheckInItemRequest(category, abnormal, value, note);
    }

    private ApiClient.ApiCall submit(String token, long petId, LocalDate date, CheckInItemRequest item) {
        return api.post("/api/v1/app/pets/" + petId + "/check-ins",
                new CheckInSubmitRequest(date.toString(), List.of(item)), token);
    }

    private static void assertCodeOk(ApiClient.ApiCall call, String what) {
        if (call.code() != 0) {
            throw new AssertionError(what + " 应当成功，实际：HTTP " + call.status() + " " + call.body());
        }
    }
}
