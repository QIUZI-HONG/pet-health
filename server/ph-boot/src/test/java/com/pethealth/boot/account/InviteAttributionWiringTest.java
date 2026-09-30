package com.pethealth.boot.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethealth.api.app.CheckInItemRequest;
import com.pethealth.api.app.CheckInSubmitRequest;
import com.pethealth.api.app.InviteAttributionRequest;
import com.pethealth.api.app.RegisterRequest;
import com.pethealth.boot.support.ApiClient;
import com.pethealth.boot.support.IntegrationTestBase;
import com.pethealth.common.time.AppTime;
import com.pethealth.privilege.api.InviteAttributionApi;
import com.pethealth.privilege.api.PointsApi;
import com.pethealth.privilege.service.InviteSettlementJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 邀请归因的接线验证（ADR-0039 第一节 / ADR-0046「需要协调」第 1、2 条）。
 *
 * <p>一条用例把整条链路跑完，每一步都走**真实 HTTP**：
 *
 * <pre>
 *   注册（ph-account）→ 归因（POST /invites/attribution → InviteAttributionApi）
 *                    → 建档（POST /pets → markProfileCompleted）
 *                    → 打卡（行为证据，ph-record → PointsApi）
 *                    → 结算批算（每小时那条，观察窗结束后判有效）
 * </pre>
 *
 * <p>三个刻意的验证点：
 *
 * <ul>
 *   <li><b>归因只有一次机会</b>：重复提交（哪怕换一个码）都返回 {@code ALREADY_ATTRIBUTED}
 *       且不改写已建立的关系——这就是「不做事后补填」在接口层的落点；
 *   <li><b>建档才是「有效邀请」的门槛</b>：建档前关系停在「待生效」；建档记下时刻，
 *       但**是否有效还要看观察窗内有没有行为**（ADR-0046 第二节）；
 *   <li><b>被反作弊/坏码拦下时注册照常成功</b>：{@code attributed=false} 是业务结果，不是错误。
 * </ul>
 *
 * <p>24 小时观察窗与「有行为」的判据：用例把归因时刻与行为流水回拨到窗口内
 * （契约没有「注册时刻」入参，服务端取的是 {@code user.created_at}），这样跑的是**生产那条批算**，
 * 而不是一个只会在测试里成立的假窗口。
 */
class InviteAttributionWiringTest extends IntegrationTestBase {

    private ApiClient api;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InviteAttributionApi inviteApi;

    @Autowired
    private PointsApi pointsApi;

    @Autowired
    private InviteSettlementJob settlementJob;

    @BeforeEach
    void setUpInvite() {
        api = new ApiClient(rest, objectMapper);
        // 邀请与积分那几张表不在 IntegrationTestBase 的清理名单里（跨切片共享的基类），本切片自己清。
        // 种子（point_behavior / point_config / invite_ladder_tier）不动，只复位用例可能改过的字段
        jdbc.execute("DELETE FROM `coupon`");
        jdbc.execute("DELETE FROM `invite_risk_record`");
        jdbc.execute("DELETE FROM `invite_ladder_achievement`");
        jdbc.execute("DELETE FROM `invite_relation`");
        jdbc.execute("DELETE FROM `invite_code`");
        jdbc.execute("DELETE FROM `point_record`");
        jdbc.execute("DELETE FROM `user_point`");
        jdbc.execute("UPDATE `point_behavior` SET `status` = 1, `points` = 3 WHERE `code` = 'CHECK_IN'");
        jdbc.execute("UPDATE `invite_ladder_tier` SET `reward_type` = NULL, `coupon_template_id` = NULL, "
                + "`rights_code` = NULL, `reward_count` = 1, `status` = 1");
    }

    @Test
    @DisplayName("注册 → 归因（待生效）→ 建档 → 打卡 → 结算：关系转有效，邀请人拿 20 分")
    void registerToProfileTurnsPendingIntoEffective() {
        // 邀请人先有码（C 端「我的邀请码」入口属下一波，这里用同一份 api 造数据）
        long inviterId = registerUser("13720000001");
        String inviteCode = inviteApi.ensureInviteCode(inviterId, 1, "device-inviter",
                "10.20.30.40", "1380001");

        // 被邀请人：注册 → 立刻归因一次（契约规定的那一次调用）
        ApiClient.ApiCall inviteeRegistration = api.post("/api/v1/app/auth/register",
                new RegisterRequest("13720000002", ApiClient.DEFAULT_PASSWORD, null));
        assertThat(inviteeRegistration.code()).isZero();
        String inviteeToken = inviteeRegistration.data().path("access_token").asText();
        long inviteeId = inviteeRegistration.data().path("user").path("id").asLong();

        ApiClient.ApiCall attributed = attribute(inviteeToken, inviteCode, 2, "device-invitee");
        assertThat(attributed.code()).isZero();
        assertThat(attributed.data().path("attributed").asBoolean()).isTrue();
        assertThat(attributed.data().path("relation_status").asInt()).isEqualTo(1);   // 待生效
        assertThat(attributed.data().path("invite_code").asText()).isEqualTo(inviteCode);
        assertThat(attributed.data().path("reason").isNull()).isTrue();
        // 给用户看的那句话在 data.notice 里（信封的 message 在 code=0 时到不了前端，
        // 见 contract/common.yaml 的 ApiResponse.message）——两处同一句，前端读 data
        assertThat(attributed.data().path("notice").asText()).isEqualTo("邀请关系已记录，待生效");
        assertThat(attributed.message()).isEqualTo(attributed.data().path("notice").asText());

        // 关系落成「待生效」，归因时刻取的是注册时刻（不是这次请求的时刻）
        assertThat(statusOf(inviteeId)).isEqualTo(1);
        assertThat(profileCompletedAtOf(inviteeId)).isNull();
        assertThat(jdbc.queryForObject("SELECT `attributed_at` FROM `invite_relation` "
                + "WHERE `invitee_user_id` = ?", LocalDateTime.class, inviteeId)).isNotNull();

        // 归因只有一次机会：重复提交、甚至换一个人自己的码，都不改写已有关系
        long otherInviter = registerUser("13720000003");
        String otherCode = inviteApi.ensureInviteCode(otherInviter, 2, "device-other", "10.20.30.41", "1390001");
        ApiClient.ApiCall again = attribute(inviteeToken, otherCode, 2, "device-invitee");
        assertThat(again.code()).isZero();
        assertThat(again.data().path("attributed").asBoolean()).isFalse();
        assertThat(again.data().path("reason").asText()).isEqualTo("ALREADY_ATTRIBUTED");
        assertThat(again.message()).isEqualTo("该账号已绑定邀请关系");
        assertThat(again.data().path("notice").asText()).isEqualTo("该账号已绑定邀请关系");
        assertThat(jdbc.queryForObject("SELECT `inviter_user_id` FROM `invite_relation` "
                + "WHERE `invitee_user_id` = ?", Long.class, inviteeId)).isEqualTo(inviterId);

        // 建档：markProfileCompleted 在这里触发（POST /pets 是它的唯一入口）
        ApiClient.ApiCall pet = api.post("/api/v1/app/pets",
                new com.pethealth.api.app.PetCreateRequest("旺财", 1, null, 0, null, null, null, null, null, null),
                inviteeToken);
        assertThat(pet.code()).isZero();
        long petId = pet.data().path("id").asLong();
        assertThat(profileCompletedAtOf(inviteeId)).isNotNull();
        assertThat(statusOf(inviteeId)).isEqualTo(1);   // 建档只是门槛，观察窗还没走完
        assertThat(pointsApi.balanceOf(inviterId)).isZero();

        // 观察窗内的行为证据：打卡（打卡 → 积分流水，见 CheckInPointsWiringTest）
        ApiClient.ApiCall checkIn = api.post("/api/v1/app/pets/" + petId + "/check-ins",
                new CheckInSubmitRequest(AppTime.today().toString(),
                        List.of(new CheckInItemRequest(2, false, "normal", null))), inviteeToken);
        assertThat(checkIn.code()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `point_record` WHERE `user_id` = ?",
                Integer.class, inviteeId)).isEqualTo(1);

        // 把归因时刻与行为流水移进观察窗（窗口 = 归因后 24 小时），再跑**生产那条批算**
        jdbc.update("UPDATE `invite_relation` SET `attributed_at` = ? WHERE `invitee_user_id` = ?",
                AppTime.now().minusHours(25), inviteeId);
        jdbc.update("UPDATE `point_record` SET `created_at` = ? WHERE `user_id` = ?",
                AppTime.now().minusHours(20), inviteeId);
        settlementJob.scheduled();

        assertThat(statusOf(inviteeId)).isEqualTo(2);   // 有效
        assertThat(jdbc.queryForObject("SELECT `reject_reason` FROM `invite_relation` "
                + "WHERE `invitee_user_id` = ?", String.class, inviteeId)).isNull();
        assertThat(jdbc.queryForObject("SELECT `settled_at` FROM `invite_relation` "
                + "WHERE `invitee_user_id` = ?", LocalDateTime.class, inviteeId)).isNotNull();
        // 有效邀请给邀请人 20 分，且**不占**每日上限（ADR-0038 第四节）
        assertThat(pointsApi.balanceOf(inviterId)).isEqualTo(20);
        assertThat(pointsApi.todayEarned(inviterId)).isZero();
        // 结算幂等：再跑一次没有候选、不重复发分
        settlementJob.scheduled();
        assertThat(pointsApi.balanceOf(inviterId)).isEqualTo(20);
    }

    @Test
    @DisplayName("坏码与缺参：注册照常成功、归因返回业务结果；请求体缺字段才 40001")
    void badCodeIsABusinessResultNotAnError() {
        String token = registerWithToken("13720000011");

        ApiClient.ApiCall notFound = attribute(token, "NOSUCHCODE", 2, null);
        assertThat(notFound.code()).isZero();
        assertThat(notFound.data().path("attributed").asBoolean()).isFalse();
        assertThat(notFound.data().path("reason").asText()).isEqualTo("CODE_NOT_FOUND");
        assertThat(notFound.message()).isEqualTo("邀请码不存在或已失效");
        assertThat(notFound.data().path("notice").asText()).isEqualTo("邀请码不存在或已失效");
        // 一条关系都没有，但账号好好的（不能因为邀请码可疑就不让用户注册）
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `invite_relation`", Integer.class)).isZero();

        // 契约的 required（invite_code / channel）由 DTO 的校验注解守住
        ApiClient.ApiCall missing = api.post("/api/v1/app/invites/attribution",
                new InviteAttributionRequest("  ", null, null), token);
        assertThat(missing.code()).isEqualTo(40001);
    }

    // ---------------------------------------------------------------- 工具

    private ApiClient.ApiCall attribute(String token, String code, Integer channel, String deviceId) {
        return api.post("/api/v1/app/invites/attribution",
                new InviteAttributionRequest(code, channel, deviceId), token);
    }

    private long registerUser(String phone) {
        return api.register(phone).data().path("user").path("id").asLong();
    }

    private String registerWithToken(String phone) {
        return api.registerAndGetAccessToken(phone);
    }

    private int statusOf(long inviteeUserId) {
        return jdbc.queryForObject("SELECT `status` FROM `invite_relation` WHERE `invitee_user_id` = ?",
                Integer.class, inviteeUserId);
    }

    private LocalDateTime profileCompletedAtOf(long inviteeUserId) {
        return jdbc.queryForObject("SELECT `profile_completed_at` FROM `invite_relation` "
                + "WHERE `invitee_user_id` = ?", LocalDateTime.class, inviteeUserId);
    }
}
