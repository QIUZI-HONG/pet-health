package com.pethealth.boot.privilege;

import com.pethealth.api.app.CheckInItemRequest;
import com.pethealth.api.app.CheckInSubmitRequest;
import com.pethealth.api.app.InviteAttributionRequest;
import com.pethealth.api.app.InviteCodeRequest;
import com.pethealth.api.app.PetCreateRequest;
import com.pethealth.boot.support.ApiClient;
import com.pethealth.boot.support.RepoRoot;
import com.pethealth.common.time.AppTime;
import com.pethealth.privilege.api.PointsApi;
import com.pethealth.privilege.domain.PointBehavior;
import com.pethealth.privilege.service.InviteSettlementJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 首批增长配置（{@code V39__first_batch_growth_config.sql}）的自检：**执行真正的迁移文件**，
 * 再断言它配出来的东西**真的能用**。
 *
 * <p>为什么要单独盯这一层：那批配置是「开箱可用」与「只能自己写 SQL」之间的差别
 * （ADR-0046 裁决过「不编数值」，所以给的是首批暂定值）。而它配错了不会有任何症状——
 * 券模板指向一个不存在的分类编码、档位指向一张停用的模板，界面照常显示，只是发不出东西。
 * 这类失败正是本仓反复吃亏的那一型：**机制在、配置在、但没有一条用例在证明它们对得上**。
 *
 * <p>为什么在测试里重新执行一遍迁移文件（而不是让断言去读测试自己抄的一份 SQL）：
 * 基类 {@link PrivilegeTestSupport} 每个用例前都会清空券模板与档位（那是为了让每个用例
 * 自己造数据），所以这里必须把**仓库里那一份**重新装回去——断言的对象因此始终是
 * 迁移文件本身，改坏了文件这条用例就会红。**抄一份到测试里就失去了这个性质。**
 */
class FirstBatchGrowthConfigTest extends PrivilegeTestSupport {

    /** 首批配置的迁移文件（相对仓库根）。 */
    private static final Path MIGRATION =
            Path.of("server/ph-boot/src/main/resources/db/migration/V39__first_batch_growth_config.sql");

    @Autowired
    private PointsApi pointsApi;

    /** 结算批算的 bean——与生产同一个（不是测试里另写的等价物）。 */
    @Autowired
    private InviteSettlementJob settlementJob;

    @BeforeEach
    void reseedFirstBatchConfig() throws IOException {
        jdbc.execute("DELETE FROM `coupon_template` WHERE `code` IN ('CP-101', 'CP-102')");
        for (String statement : statementsOf(RepoRoot.find().resolve(MIGRATION))) {
            jdbc.execute(statement);
        }
    }

    @Test
    @DisplayName("配置自洽：奖励券存在且是平台补贴券、适用范围在标准目录里、三处引用都指得到")
    void seededConfigIsCoherent() {
        // 1) 奖励券本身：成本归平台（阶梯与兑换都由平台出），且是启用状态
        List<Map<String, Object>> templates = jdbc.queryForList(
                "SELECT `code`, `cost_bearer`, `status`, `scope_type`, `scope_codes` FROM `coupon_template` "
                        + "WHERE `code` IN ('CP-101', 'CP-102')");
        assertThat(templates).as("首批两张奖励券都在").hasSize(2);
        assertThat(templates).allSatisfy(row -> {
            assertThat(((Number) row.get("cost_bearer")).intValue())
                    .as("奖励券必须成本归平台——挂服务者成本的话，发出去就等于让服务者买单")
                    .isEqualTo(2);
            assertThat(((Number) row.get("status")).intValue()).isEqualTo(1);
        });

        // 2) 适用范围引用的分类编码必须真的存在（写错了不会报错，只会永远匹配不上任何门店）
        Map<String, Object> wash = templates.stream()
                .filter(row -> "CP-101".equals(row.get("code"))).findFirst().orElseThrow();
        assertThat(((Number) wash.get("scope_type")).intValue()).isEqualTo(1);
        for (String code : String.valueOf(wash.get("scope_codes")).split(",")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `service_category` WHERE `code` = ? AND `status` = 1",
                    Integer.class, code.trim()))
                    .as("适用范围编码 %s 在标准目录里存在且启用", code)
                    .isEqualTo(1);
        }

        // 3) 三处引用都指得到那张券（邀请阶梯 / 积分兑换 / 月度阶梯）
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `invite_ladder_tier` "
                + "WHERE `threshold` IN (1, 3, 5, 10, 15) AND `reward_type` = 1 AND `coupon_template_id` IS NOT NULL "
                + "AND `status` = 1", Integer.class)).as("五档邀请阶梯都配了奖励").isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `point_exchange_option` "
                + "WHERE `coupon_template_id` IN (SELECT `id` FROM `coupon_template`) AND `status` = 1",
                Integer.class)).as("兑换档位都指向存在的券模板").isPositive();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `point_ladder_tier` "
                + "WHERE `coupon_template_id` IN (SELECT `id` FROM `coupon_template`) AND `status` = 1",
                Integer.class)).as("月度阶梯都指向存在的券模板").isPositive();
        assertThat(jdbc.queryForObject("SELECT `points` FROM `point_behavior` WHERE `code` = 'INVITE_INVITEE'",
                Integer.class)).as("被邀请人的那份奖励已启用并给了分值").isPositive();
    }

    @Test
    @DisplayName("积分真的能花：用首批档位兑换，扣分与发券在同一笔里完成")
    void seededExchangeOptionActuallySpendsPoints() {
        String token = api.registerAndGetAccessToken("13800003311");
        long userId = jdbc.queryForObject("SELECT `id` FROM `user` WHERE `is_deleted` = 0 ORDER BY `id` DESC LIMIT 1",
                Long.class);
        // 攒到 30 分：邀请有效注册 20 + 完善档案 10（都用生产那条 award，不直接改余额）
        pointsApi.award(new PointsApi.AwardCommand(userId, PointBehavior.INVITE, "test:first-batch-invite", null));
        pointsApi.award(new PointsApi.AwardCommand(userId, PointBehavior.PROFILE_COMPLETE, "test:first-batch-profile",
                null));
        assertThat(pointsApi.balanceOf(userId)).isEqualTo(30);

        Long optionId = jdbc.queryForObject("SELECT `id` FROM `point_exchange_option` "
                + "WHERE `points_cost` = 30 AND `status` = 1 ORDER BY `sort_order` LIMIT 1", Long.class);
        assertThat(optionId).as("首批配置里 30 分那一档存在").isNotNull();

        ApiClient.ApiCall exchanged = api.post("/api/v1/app/points/exchange", Map.of("option_id", optionId), token);
        assertThat(exchanged.code()).isZero();
        assertThat(exchanged.data().path("balance_after").asInt()).as("扣分").isZero();
        assertThat(exchanged.data().path("coupon").path("template_code").asText())
                .as("兑出的券来自首批那张通用券").isEqualTo("CP-102");
        assertThat(pointsApi.balanceOf(userId)).isZero();
    }

    @Test
    @DisplayName("邀请阶梯真的会发：达成的档位按首批配置发出一张洗护券")
    void seededLadderTierActuallyIssuesACoupon() {
        // 走生产那条结算路径：注册 → 归因 → 建档 → 打卡 → 把时刻回拨进观察窗 → 结算
        String inviterToken = api.registerAndGetAccessToken("13800004411");
        long inviterId = lastUserId();
        String code = api.post("/api/v1/app/invites/code",
                new InviteCodeRequest(1, "dev-first-batch"), inviterToken).data().path("code").asText();

        String inviteeToken = api.registerAndGetAccessToken("13900004411");
        long inviteeId = lastUserId();
        assertThat(api.post("/api/v1/app/invites/attribution",
                new InviteAttributionRequest(code, 2, "dev-first-batch-invitee"), inviteeToken)
                .data().path("attributed").asBoolean()).isTrue();

        long petId = api.post("/api/v1/app/pets",
                new PetCreateRequest("首批配置", 1, null, 0, null, null, null, null, null, null), inviteeToken)
                .data().path("id").asLong();
        assertThat(api.post("/api/v1/app/pets/" + petId + "/check-ins",
                new CheckInSubmitRequest(AppTime.today().toString(),
                        List.of(new CheckInItemRequest(2, false, "normal", null))), inviteeToken).code()).isZero();

        jdbc.update("UPDATE `invite_relation` SET `attributed_at` = ? WHERE `invitee_user_id` = ?",
                AppTime.now().minusHours(25), inviteeId);
        jdbc.update("UPDATE `point_record` SET `created_at` = ? WHERE `user_id` = ?",
                AppTime.now().minusHours(20), inviteeId);
        settlementJob.scheduled();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `coupon` WHERE `user_id` = ? "
                + "AND `template_id` = (SELECT `id` FROM `coupon_template` WHERE `code` = 'CP-101')",
                Integer.class, inviterId))
                .as("1 人档达成 → 按首批配置发出 1 张洗护券")
                .isEqualTo(1);
    }

    // ---------------------------------------------------------------- 工具

    private long lastUserId() {
        return jdbc.queryForObject("SELECT `id` FROM `user` WHERE `is_deleted` = 0 ORDER BY `id` DESC LIMIT 1",
                Long.class);
    }

    /** 把一份迁移 SQL 拆成可逐条执行的语句（去掉注释行，按分号切）。 */
    private static List<String> statementsOf(Path file) throws IOException {
        String sql = Files.readString(file);
        StringBuilder stripped = new StringBuilder();
        for (String line : sql.split("\n")) {
            if (!line.trim().startsWith("--")) {
                stripped.append(line).append('\n');
            }
        }
        List<String> statements = new ArrayList<>();
        for (String statement : stripped.toString().split(";")) {
            if (!statement.isBlank()) {
                statements.add(statement.trim());
            }
        }
        return statements;
    }
}
