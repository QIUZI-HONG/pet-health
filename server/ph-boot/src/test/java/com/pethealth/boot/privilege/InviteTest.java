package com.pethealth.boot.privilege;

import com.pethealth.privilege.api.InviteAttributionApi;
import com.pethealth.boot.support.ApiClient;
import com.pethealth.privilege.api.PointsApi;
import com.pethealth.privilege.domain.PointBehavior;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 邀请：归因、有效邀请结算与阶梯（切片 #111，决策见 ADR-0039 第一节 / ADR-0046）。
 *
 * <p>这一层盯 ADR-0039 的三条：
 *
 * <ul>
 *   <li><b>归因只在注册那一刻</b>：一个被邀请人只会被归因一次，重复归因返回 ALREADY_ATTRIBUTED；
 *   <li><b>有效邀请 = 完成建档 + 24 小时内有行为</b>：所以关系先「待生效」，
 *       结算时才判有效 / 无效；**24 小时内无行为的一律不发奖**；
 *   <li><b>反作弊三层</b>：自邀自、同设备、同 IP + 同号段都在归因那一刻拦下，
 *       命中记一条可查的判据记录。
 * </ul>
 *
 * <p>注册与建档的入口分别在 ph-account 与 ph-record（本次不改），所以这里直接调
 * {@link InviteAttributionApi}——**接线点与要改的那一行写在报告里**（见「需要协调」）。
 * 断言仍然只看外部可观察状态：关系状态、风险记录、积分流水、券与阶梯达成记录。
 */
class InviteTest extends PrivilegeTestSupport {

    @Autowired
    private InviteAttributionApi inviteApi;

    @Autowired
    private PointsApi pointsApi;

    @Test
    @DisplayName("归因：注册时归因 → 待生效；完成建档仍是待生效；24 小时内有行为才算有效并给邀请人发 20 分")
    void attributionNeedsProfileAndActivity() {
        long inviter = 820_001L;
        String code = inviteApi.ensureInviteCode(inviter, 1, "device-inviter", "10.0.0.1", "1380000");

        long invitee = 820_002L;
        InviteAttributionApi.Attribution attributed = inviteApi.attribute(new InviteAttributionApi.AttributionCommand(code, invitee, 2, "device-invitee", "10.0.0.2", "1390000",
                LocalDateTime.now().minusHours(25)));
        assertThat(attributed.attributed()).isTrue();
        assertThat(statusOf(invitee)).isEqualTo(1);

        // 归因只做一次（不做事后补填）
        InviteAttributionApi.Attribution again = inviteApi.attribute(new InviteAttributionApi.AttributionCommand(code, invitee, 2, "device-invitee", "10.0.0.2", "1390000",
                LocalDateTime.now()));
        assertThat(again.attributed()).isFalse();
        assertThat(again.reason()).isEqualTo("ALREADY_ATTRIBUTED");

        // 完成建档：推进到「已有建档时刻」，但仍然待生效（要等观察窗）
        assertThat(inviteApi.markProfileCompleted(invitee)).isTrue();
        assertThat(statusOf(invitee)).isEqualTo(1);
        assertThat(inviteApi.markProfileCompleted(invitee)).isTrue(); // 幂等

        // 观察窗内有一次业务行为（打卡）：这是「有行为」的证据。
        // 流水要回拨到观察窗里——归因时刻在 25 小时前，此刻打的卡落在窗口之外
        LocalDateTime attributedAt = jdbc.queryForObject(
                "SELECT attributed_at FROM invite_relation WHERE invitee_user_id = ?", LocalDateTime.class, invitee);
        pointsApi.award(new PointsApi.AwardCommand(invitee, PointBehavior.CHECK_IN, "checkin:1", null));
        backdateActivity(invitee, attributedAt.plusHours(2));

        // 结算：扫描 1 条 → 有效 1 条
        InviteAttributionApi.SettlementResult result = inviteApi.settle(100);
        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.effective()).isEqualTo(1);
        assertThat(statusOf(invitee)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT reject_reason FROM invite_relation WHERE invitee_user_id = ?",
                String.class, invitee)).isNull();

        // 邀请人拿到 20 分（不占每日上限）
        assertThat(pointsApi.balanceOf(inviter)).isEqualTo(20);
        assertThat(pointsApi.todayEarned(inviter)).isZero();

        // 邀请进度通知（F026）：给邀请人发一条站内消息，正文带上当前有效人数
        Long relationId = jdbc.queryForObject("SELECT id FROM invite_relation WHERE invitee_user_id = ?",
                Long.class, invitee);
        assertThat(messageCount(inviter, "invite-effective-" + relationId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT content FROM `message` WHERE user_id = ? AND dedup_key = ?",
                String.class, inviter, "invite-effective-" + relationId))
                .as("正文要给出当前有效人数——「进度」不进正文的话，这条消息就只是句废话")
                .contains("当前有效 1 人");

        // 结算幂等：再跑一次没有候选
        InviteAttributionApi.SettlementResult second = inviteApi.settle(100);
        assertThat(second.scanned()).isZero();
        assertThat(pointsApi.balanceOf(inviter)).isEqualTo(20);

        // 运营总览：注册 1、有效 1、有效率 1.00；阶梯 1 档达成 1 人（未配奖励所以没发东西）
        ApiClient.ApiCall overview = api.get("/api/v1/admin/invites/overview", adminToken());
        assertThat(overview.data().path("registered_count").asInt()).isEqualTo(1);
        assertThat(overview.data().path("effective_count").asInt()).isEqualTo(1);
        assertThat(overview.data().path("valid_rate").asText()).isEqualTo("1.00");
        assertThat(overview.data().path("ladder_stats").get(0).path("threshold").asInt()).isEqualTo(1);
        assertThat(overview.data().path("ladder_stats").get(0).path("achieved_count").asInt()).isEqualTo(1);

        // 达成记录在，但没配奖励 → 没有券、没有权益
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invite_ladder_achievement WHERE user_id = ?",
                Long.class, inviter)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT coupon_id FROM invite_ladder_achievement WHERE user_id = ?",
                Long.class, inviter)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon", Long.class)).isZero();
    }

    @Test
    @DisplayName("24 小时内无行为：完成建档也不发奖，判无效并记 NO_ACTIVITY_24H")
    void noActivityWithinWindowIsInvalid() {
        long inviter = 820_010L;
        String code = inviteApi.ensureInviteCode(inviter, 1, "dev-a", "10.0.1.1", "1380001");
        long invitee = 820_011L;
        inviteApi.attribute(new InviteAttributionApi.AttributionCommand(code, invitee, 1, "dev-b",
                "10.0.1.2", "1390001", LocalDateTime.now().minusHours(26)));
        inviteApi.markProfileCompleted(invitee);

        // 只有「完成建档」这一条流水——它不算「有行为」（否则这条规则自动失效）
        pointsApi.award(new PointsApi.AwardCommand(invitee, PointBehavior.PROFILE_COMPLETE, "profile:1", null));

        InviteAttributionApi.SettlementResult result = inviteApi.settle(100);
        assertThat(result.invalid()).isEqualTo(1);
        assertThat(statusOf(invitee)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT reject_reason FROM invite_relation WHERE invitee_user_id = ?",
                String.class, invitee)).isEqualTo("NO_ACTIVITY_24H");

        // 不发分、不计数
        assertThat(pointsApi.balanceOf(inviter)).isZero();
        ApiClient.ApiCall relations = api.get("/api/v1/admin/invites/relations?status=3", adminToken());
        assertThat(relations.data().path("total").asLong()).isEqualTo(1);

        // 反作弊记录可按判据查出「是哪一类」
        ApiClient.ApiCall risks = api.get("/api/v1/admin/invites/risk-records?rule=NO_ACTIVITY_24H",
                adminToken());
        assertThat(risks.data().path("total").asLong()).isEqualTo(1);
        assertThat(risks.data().path("list").get(0).path("inviter_user_id").asLong()).isEqualTo(inviter);
    }

    @Test
    @DisplayName("反作弊：自邀自、同设备、同 IP + 同号段都在归因那一刻拦下，并留下判据记录")
    void antiCheatRules() {
        long inviter = 820_020L;
        // 公网地址（TEST-NET-3，文档专用段）：IP + 号段那一层要求地址**真的能区分来源**，
        // 私网/回环/CGNAT 是被大量用户共享的，拿它们比没有意义（见 InviteRiskGuard 的类注释）
        String code = inviteApi.ensureInviteCode(inviter, 1, "device-shared", "203.0.113.9", "1380002");

        // 1) 自邀自
        InviteAttributionApi.Attribution self = inviteApi.attribute(new InviteAttributionApi.AttributionCommand(code, inviter, 1, "device-shared", "203.0.113.9", "1380002",
                LocalDateTime.now()));
        assertThat(self.attributed()).isFalse();
        assertThat(self.reason()).isEqualTo("SELF_INVITE");

        // 2) 同设备（不同账号、不同 IP、不同号段）
        InviteAttributionApi.Attribution sameDevice = inviteApi.attribute(new InviteAttributionApi.AttributionCommand(code, 820_021L, 1, "device-shared", "10.0.2.9", "1390009",
                LocalDateTime.now()));
        assertThat(sameDevice.reason()).isEqualTo("SAME_DEVICE");

        // 3) 同 IP + 同号段（设备不同）
        InviteAttributionApi.Attribution sameIpSegment = inviteApi.attribute(new InviteAttributionApi.AttributionCommand(code, 820_022L, 1, "device-other", "203.0.113.9", "1380002",
                LocalDateTime.now()));
        assertThat(sameIpSegment.reason()).isEqualTo("SAME_IP_SEGMENT");

        // 三条命中都记了记录；被拦下的邀请**没有产生关系行**（不计数）
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invite_risk_record", Long.class)).isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invite_relation", Long.class)).isZero();

        // 4) 只看 IP 相同、号段不同 → 不拦（共用 WiFi 的同事不该被误伤）
        InviteAttributionApi.Attribution sameIpOnly = inviteApi.attribute(new InviteAttributionApi.AttributionCommand(code, 820_023L, 1, "device-x", "203.0.113.9", "1390003",
                LocalDateTime.now()));
        assertThat(sameIpOnly.attributed()).isTrue();

        // 5) 私网/回环地址不判这一层：地址本身不区分来源（办公室内网、本机、代理之后的同一个地址），
        //    拿它当「同一个出口」的判据会让规则退化成「同号段就拦」——比 ADR-0046 想要的严得多
        InviteAttributionApi.Attribution privateAddress = inviteApi.attribute(
                new InviteAttributionApi.AttributionCommand(code, 820_025L, 1, "device-priv", "10.0.2.1", "1380002",
                        LocalDateTime.now()));
        assertThat(privateAddress.attributed()).isTrue();
        InviteAttributionApi.Attribution loopback = inviteApi.attribute(
                new InviteAttributionApi.AttributionCommand(code, 820_026L, 1, "device-loop", "127.0.0.1", "1380002",
                        LocalDateTime.now()));
        assertThat(loopback.attributed()).isTrue();

        // 6) 码不存在：既不归因也不记风险（那是用户填错了，不是作弊）
        InviteAttributionApi.Attribution unknown = inviteApi.attribute(new InviteAttributionApi.AttributionCommand("NOSUCHCD", 820_024L, 2, "device-y", "10.0.2.7", "1390004",
                LocalDateTime.now()));
        assertThat(unknown.reason()).isEqualTo("CODE_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invite_risk_record", Long.class)).isEqualTo(3L);

        // 一人一码：重复取回同一个码
        assertThat(inviteApi.ensureInviteCode(inviter, 1, "device-shared", "203.0.113.9", "1380002"))
                .isEqualTo(code);
    }

    @Test
    @DisplayName("阶梯：配了奖励就发（券只发一次）；1/3/5/10/15 五档各自只结算一次")
    void ladderRewardsAreIssuedOnce() {
        String admin = adminToken();
        // 平台补贴券：阶梯奖的成本归平台
        long subsidyTemplateId = api.post("/api/v1/admin/coupon-templates", Map.of(
                        "code", "CP-201", "name", "邀请阶梯券", "face_value", "20.00", "min_amount", "0.00",
                        "valid_days", 30, "cost_bearer", 2), admin)
                .data().path("id").asLong();
        // 给第 1 档配上券
        assertCodeOk(api.put("/api/v1/admin/invites/ladder-tiers/1", Map.of(
                "reward_type", 1, "coupon_template_id", subsidyTemplateId, "reward_count", 1, "status", 1),
                admin), "配置第 1 档奖励");
        ApiClient.ApiCall tiers = api.get("/api/v1/admin/invites/ladder-tiers", admin);
        assertThat(tiers.data()).hasSize(5);
        assertThat(tiers.data().get(0).path("coupon_template_name").asText()).isEqualTo("邀请阶梯券");
        assertThat(tiers.data().get(1).path("reward_type").isNull()).isTrue();

        long inviter = 820_030L;
        String code = inviteApi.ensureInviteCode(inviter, 1, "dev-l", "10.0.3.1", "1380003");
        long invitee = 820_031L;
        arrive(code, invitee, "dev-m", "10.0.3.2", "1390005");

        // 第 1 档达成 → 发一张券
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT source FROM coupon", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT source_ref FROM coupon", String.class))
                .isEqualTo("invite-ladder:" + inviter + ":1");

        // 第二个有效邀请：1 档已发过（不重复发），3 档还没到
        long invitee2 = 820_032L;
        arrive(code, invitee2, "dev-n", "10.0.3.3", "1390006");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invite_ladder_achievement", Long.class))
                .isEqualTo(1L);

        // 第三个有效邀请 → 3 档达成，但那一档没配奖励 → 只记达成、不发东西
        long invitee3 = 820_033L;
        arrive(code, invitee3, "dev-o", "10.0.3.4", "1390007");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invite_ladder_achievement", Long.class))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT reward_type FROM invite_ladder_achievement "
                + "WHERE threshold = 3", Integer.class)).isNull();

        // 门槛是固定五档：能配的是「每档发什么」，不是门槛本身
        assertCodeOk(api.put("/api/v1/admin/invites/ladder-tiers/3", Map.of(
                "reward_type", 2, "rights_code", "ai.unlimited", "status", 1), admin), "给 3 档配权益");
        ApiClient.ApiCall tier3 = api.get("/api/v1/admin/invites/ladder-tiers", admin);
        assertThat(tier3.data().get(1).path("rights_code").asText()).isEqualTo("ai.unlimited");
        assertThat(tier3.data().get(1).path("rights_name").asText()).isEqualTo("无限 AI 问答");
        // 不存在的档位（门槛是 1/3/5/10/15，没有 2）→ 40400
        assertThat(api.put("/api/v1/admin/invites/ladder-tiers/2", Map.of(
                "reward_type", 1, "coupon_template_id", subsidyTemplateId, "status", 1), admin).code())
                .isEqualTo(40400);
        assertThat(api.put("/api/v1/admin/invites/ladder-tiers/7", Map.of(
                "reward_type", 1, "coupon_template_id", subsidyTemplateId, "status", 1), admin).code())
                .isEqualTo(40400);
        // 发券档位必须给券模板；授权益必须给存在的权益码
        assertThat(api.put("/api/v1/admin/invites/ladder-tiers/5", Map.of(
                "reward_type", 1, "status", 1), admin).code()).isEqualTo(40001);
        assertThat(api.put("/api/v1/admin/invites/ladder-tiers/5", Map.of(
                "reward_type", 2, "rights_code", "nope.nope", "status", 1), admin).code()).isEqualTo(40001);
    }

    // ---------------------------------------------------------------- 工具

    /** 走完一条有效邀请的全程：归因（25 小时前）→ 建档 → 观察窗内有一次行为 → 结算。 */
    private void arrive(String code, long invitee, String deviceId, String ip, String segment) {
        LocalDateTime attributedAt = LocalDateTime.now().minusHours(25);
        assertThat(inviteApi.attribute(new InviteAttributionApi.AttributionCommand(code, invitee, 1,
                deviceId, ip, segment, attributedAt)).attributed()).isTrue();
        inviteApi.markProfileCompleted(invitee);
        // 「有行为」判的是**观察窗内**（归因后 24 小时）有没有流水，所以这条打卡要回拨到窗口里：
        // 直接此刻打卡的话，它在窗口之外——那不是「没行为」，而是用例没把时间造对
        pointsApi.award(new PointsApi.AwardCommand(invitee, PointBehavior.CHECK_IN,
                "checkin:" + invitee, null));
        backdateActivity(invitee, attributedAt.plusHours(2));
        InviteAttributionApi.SettlementResult result = inviteApi.settle(100);
        assertThat(result.effective()).isGreaterThanOrEqualTo(1);
    }

    /** 把某人的行为流水回拨到指定时刻（模拟「注册后两小时打了卡」）。 */
    @Test
    @DisplayName("被邀请人奖励：默认不发（V38 种子是「0 分 + 停用」）；运营启用并定分后结算时双方各得一份")
    void inviteeRewardFollowsTheConfig() {
        // ① 默认配置：一次有效邀请结算后，只有邀请人拿到分——被邀请人那一份是「未配置」，不是漏发
        long inviterA = 820_100L;
        String codeA = inviteApi.ensureInviteCode(inviterA, 1, "dev-ia", "10.1.0.1", "1380010");
        long inviteeA = 820_101L;
        inviteApi.attribute(new InviteAttributionApi.AttributionCommand(codeA, inviteeA, 1, "dev-ib",
                "10.1.0.2", "1390010", LocalDateTime.now().minusHours(25)));
        inviteApi.markProfileCompleted(inviteeA);
        LocalDateTime windowA = jdbc.queryForObject(
                "SELECT attributed_at FROM invite_relation WHERE invitee_user_id = ?", LocalDateTime.class, inviteeA);
        pointsApi.award(new PointsApi.AwardCommand(inviteeA, PointBehavior.CHECK_IN, "checkin:ia", null));
        backdateActivity(inviteeA, windowA.plusHours(2));

        assertThat(inviteApi.settle(100).effective()).isEqualTo(1);
        assertThat(pointsApi.balanceOf(inviterA)).isEqualTo(20);
        // 被邀请人自己打卡拿的 3 分不算数，这里要看的是「有没有那条被邀请人奖励的流水」
        assertThat(rewardPoints(inviteeA))
                .as("默认「未配置」时被邀请人不发奖励（奖励物留空是有效状态，ADR-0046 第五节）")
                .isZero();

        // ② 运营给它定了 5 分并启用：下一对邀请结算时，被邀请人也拿到
        jdbc.update("UPDATE `point_behavior` SET `points` = 5, `status` = 1 WHERE `code` = 'INVITE_INVITEE'");
        try {
            long inviterB = 820_110L;
            String codeB = inviteApi.ensureInviteCode(inviterB, 1, "dev-ic", "10.1.0.3", "1380011");
            long inviteeB = 820_111L;
            inviteApi.attribute(new InviteAttributionApi.AttributionCommand(codeB, inviteeB, 1, "dev-id",
                    "10.1.0.4", "1390011", LocalDateTime.now().minusHours(25)));
            inviteApi.markProfileCompleted(inviteeB);
            LocalDateTime windowB = jdbc.queryForObject(
                    "SELECT attributed_at FROM invite_relation WHERE invitee_user_id = ?", LocalDateTime.class, inviteeB);
            pointsApi.award(new PointsApi.AwardCommand(inviteeB, PointBehavior.CHECK_IN, "checkin:ib", null));
            backdateActivity(inviteeB, windowB.plusHours(2));

            assertThat(inviteApi.settle(100).effective()).isEqualTo(1);
            assertThat(pointsApi.balanceOf(inviterB)).isEqualTo(20);
            assertThat(rewardPoints(inviteeB))
                    .as("定分后，被邀请人与邀请人同期各得一份（F016 的「双方各得」）")
                    .isEqualTo(5);
            // 余额 = 打卡 3 分 + 被邀请人奖励 5 分
            assertThat(pointsApi.balanceOf(inviteeB)).isEqualTo(8);
        } finally {
            // 交给基座的清理前先复位，免得影响同一次运行里别的用例
            jdbc.update("UPDATE `point_behavior` SET `points` = 0, `status` = 0 WHERE `code` = 'INVITE_INVITEE'");
        }
    }

    private void backdateActivity(long userId, LocalDateTime at) {
        jdbc.update("UPDATE point_record SET created_at = ? WHERE user_id = ?", at, userId);
    }

    /**
     * 某个用户从「被邀请人奖励」上拿了多少分。
     *
     * 看**分值之和**而不是流水条数：这张表里 `points = 0` 是「记行为不发分」的合法编码
     * （种子里 `AI_ADVICE` / `SHARE` 就是 0 分），所以「未配置」下也可能有流水，但一定是 0 分。
     */
    private int rewardPoints(long userId) {
        Integer sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(`change_amount`), 0) FROM `point_record` "
                        + "WHERE `user_id` = ? AND `behavior_code` = 'INVITE_INVITEE'",
                Integer.class, userId);
        return sum == null ? 0 : sum;
    }

    private int statusOf(long inviteeUserId) {
        return jdbc.queryForObject("SELECT status FROM invite_relation WHERE invitee_user_id = ?",
                Integer.class, inviteeUserId);
    }

    /** 数一条业务通知（去重键是它在这张表里的唯一标识）。 */
    private long messageCount(long userId, String dedupKey) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM `message` WHERE user_id = ? AND dedup_key = ?",
                Long.class, userId, dedupKey);
    }
}
