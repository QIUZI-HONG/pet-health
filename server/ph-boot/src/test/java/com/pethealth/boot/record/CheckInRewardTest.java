package com.pethealth.boot.record;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethealth.api.app.CheckInItemRequest;
import com.pethealth.api.app.CheckInSubmitRequest;
import com.pethealth.boot.privilege.PrivilegeTestSupport;
import com.pethealth.boot.support.ApiClient;
import com.pethealth.boot.support.RepoRoot;
import com.pethealth.common.time.AppTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 打卡得券（交付文档 F017；V41 的 {@code check_in_reward_rule}）——来源码 2（打卡任务）
 * 在这一轮之前**从来没发过一张券**。
 *
 * <p>四条口径，每条都在防一种具体的错：
 *
 * <ul>
 *   <li><b>连续 7 天 → 1 张 {@code CP-101}</b>：档位与券取自迁移的种子；
 *   <li><b>同一轮连续只发一张</b>：第 8 天不再发。这条同时钉住了幂等引用里的
 *       **「这一轮连续的起始日」**（提交日的话，第 8 天会变成另一天的引用 → 多发一张）；
 *   <li><b>同一天重跑不重复发</b>：连续天数还是 7、规则会再命中一次，所以挡住它的是
 *       {@code coupon} 的 {@code (source, source_ref)} 唯一键（重试、并发、双击都靠它）；
 *   <li><b>断签从 1 重算、新的一轮再满 7 天可以再拿一张</b>：奖励不是「一辈子一次」，
 *       但也不是「同一天怎么刷都发」——一轮连续的起始日才是「这一次达成」的身份。
 * </ul>
 *
 * <p>造数据全部走**生产的打卡接口**（不用 SQL 直接写 {@code archive_record}）：
 * 连续天数是生产代码算的（{@code CheckInService.streak}），本切片一行都不重算——
 * 用例里自己算一遍，测的就成了「我抄的那份逻辑对不对」。
 *
 * <p>迁移文件在 {@code @BeforeEach} 里重放（与 {@code FirstBatchGrowthConfigTest} 同一套办法）：
 * 基类每个用例前清空券模板与档位，而 V41 的种子引用 V39 建的 {@code CP-101}，
 * 所以两份按顺序装回去，断言的对象始终是仓库里那两份文件。
 */
@DisplayName("打卡得券（F017）：连续 7 天一张，一轮一次，断签重算")
class CheckInRewardTest extends PrivilegeTestSupport {

    /** 打卡分项：2 饮食 / 5 情绪（六项里的两项，用来在同一只宠物同一天上打第二条）。 */
    private static final int CATEGORY_DIET = 2;
    private static final int CATEGORY_MOOD = 5;

    private static final Path V41 = Path.of(
            "server/ph-boot/src/main/resources/db/migration/V41__task_center_and_check_in_reward.sql");
    private static final Path V39 = Path.of(
            "server/ph-boot/src/main/resources/db/migration/V39__first_batch_growth_config.sql");

    @Autowired
    private ObjectMapper objectMapper;

    private ApiClient api;

    @BeforeEach
    void reseedMigrations() throws IOException {
        api = new ApiClient(rest, objectMapper);
        // 基类每个用例前清空券模板与档位；V41 的种子引用 V39 建的 `CP-101`，所以两份按顺序重装回来。
        // **先删表**：V41 里有 CREATE TABLE，重放它必须先让表不存在——顺带也验了那条 DDL 本身能跑
        // （用 `CREATE TABLE IF NOT EXISTS` 换掉的话，表结构改了测试也发现不了）
        jdbc.execute("DROP TABLE IF EXISTS `check_in_reward_rule`");
        jdbc.execute("DELETE FROM `coupon_template` WHERE `code` IN ('CP-101', 'CP-102')");
        for (String statement : statementsOf(RepoRoot.find().resolve(V39))) {
            jdbc.execute(statement);
        }
        for (String statement : statementsOf(RepoRoot.find().resolve(V41))) {
            jdbc.execute(statement);
        }
    }

    @Test
    @DisplayName("连续满 7 天发 1 张 CP-101（来源=打卡任务）；同日重跑与第 8 天都不再发")
    void sevenDayStreakIssuesExactlyOneCoupon() {
        String token = api.registerAndGetAccessToken("13710000021");
        long userId = userIdOf(token);
        long petId = api.createPet(token, "豆豆");
        LocalDate today = AppTime.today();

        // 前 6 天（today-7 .. today-2）补录进窗口。此刻连续天数还是 **0**：
        // 今天与昨天都没有记录 → 断签（「今天没打卡不算断签」只在昨天有记录时成立，ADR-0018）
        for (int back = 7; back >= 2; back--) {
            assertCodeOk(submit(token, petId, today.minusDays(back), CATEGORY_DIET), "补录 today-" + back);
        }
        assertThat(streakDays(token, petId)).isZero();
        assertThat(coupons(userId)).as("还没有连续 7 天，不发券").isEmpty();

        // 第 7 天（today-1）：连续段 today-7..today-1 成形 → 命中档位
        assertCodeOk(submit(token, petId, today.minusDays(1), CATEGORY_DIET), "第 7 天打卡");

        List<Map<String, Object>> coupons = coupons(userId);
        assertThat(coupons).as("连续满 7 天发一张").hasSize(1);
        Map<String, Object> coupon = coupons.get(0);
        assertThat(coupon.get("template_code")).as("发的是种子里的那张洗护券").isEqualTo("CP-101");
        assertThat(((Number) coupon.get("source")).intValue())
                .as("来源码 2 = 打卡任务（Coupon.SOURCE_CHECK_IN_TASK）——本轮之后它不再是死码")
                .isEqualTo(2);
        assertThat(coupon.get("source_ref"))
                .as("幂等引用 = 人 + 档位 + 这一轮连续的起始日")
                .isEqualTo("checkin-reward:" + userId + ":7:" + today.minusDays(7));
        assertThat(((Number) coupon.get("status")).intValue()).as("发出来是「待使用」").isEqualTo(1);

        // 同一天再打一项：连续天数仍是 7，**规则会再命中一次**——所以挡住重复的是幂等引用，
        // 这条断言钉的是引用本身（去掉引用里的日期成分它就会红）
        assertCodeOk(submit(token, petId, today.minusDays(1), CATEGORY_MOOD), "同一天再打一项");
        assertThat(coupons(userId)).as("同一天重跑不重复发").hasSize(1);

        // 第 8 天（今天补上）
        assertCodeOk(submit(token, petId, today, CATEGORY_DIET), "第 8 天打卡");
        assertThat(streakDays(token, petId)).as("连续到 8 天").isEqualTo(8);
        assertThat(coupons(userId)).as("第 8 天不重复发：同一轮连续只发一张").hasSize(1);
    }

    @Test
    @DisplayName("断签后连续天数重算；新的一轮再满 7 天，可以再拿一张（起始日变了）")
    void brokenStreakRecomputesAndANewRunEarnsAgain() {
        String token = api.registerAndGetAccessToken("13710000022");
        long userId = userIdOf(token);
        long petId = api.createPet(token, "豆豆");
        LocalDate today = AppTime.today();

        // 第一轮：today-7..today-1 → 拿到第一张（锚点 = today-7）
        for (int back = 7; back >= 1; back--) {
            assertCodeOk(submit(token, petId, today.minusDays(back), CATEGORY_DIET), "第一轮第 " + back + " 天");
        }
        assertThat(coupons(userId)).as("第一轮满 7 天 → 一张").hasSize(1);

        // 断在中段：撤销 today-4 与今天的起点 today-7 → 连续段只剩今天往前数的 3 天
        assertCodeOk(undo(token, petId, today.minusDays(4)), "撤销中段那天");
        assertCodeOk(undo(token, petId, today.minusDays(7)), "撤销第一轮的起点");
        assertThat(streakDays(token, petId)).as("断签后连续天数重算（不是历史最长，是当前这一段）").isEqualTo(3);

        // 攒新一轮：today-6..today（起点是 today-6，与上一轮的 today-7 不同）
        assertCodeOk(submit(token, petId, today.minusDays(4), CATEGORY_DIET), "补回断口那天");
        assertThat(streakDays(token, petId)).as("补回后 6 天：还差一天，不发券").isEqualTo(6);
        assertThat(coupons(userId)).hasSize(1);

        assertCodeOk(submit(token, petId, today, CATEGORY_DIET), "今天打卡（第 7 天）");
        assertThat(streakDays(token, petId)).as("新一轮连续满 7 天").isEqualTo(7);

        List<Map<String, Object>> coupons = coupons(userId);
        assertThat(coupons).as("新一轮的 7 天再发一张").hasSize(2);
        assertThat(coupons.get(0).get("source_ref"))
                .as("第一张的引用锚在上一轮的起始日").isEqualTo("checkin-reward:" + userId + ":7:" + today.minusDays(7));
        assertThat(coupons.get(1).get("source_ref"))
                .as("第二张锚在新一轮的起始日——两轮连续是两次达成，配两张不同的引用")
                .isEqualTo("checkin-reward:" + userId + ":7:" + today.minusDays(6));
    }

    // ---------------------------------------------------------------- 工具

    /** 打卡（生产接口），返回调用结果供断言。 */
    private ApiClient.ApiCall submit(String token, long petId, LocalDate date, int category) {
        return api.post("/api/v1/app/pets/" + petId + "/check-ins",
                new CheckInSubmitRequest(date.toString(),
                        List.of(new CheckInItemRequest(category, false, "normal", null))), token);
    }

    /** 撤销某一项（生产接口）。 */
    private ApiClient.ApiCall undo(String token, long petId, LocalDate date) {
        return api.delete("/api/v1/app/pets/" + petId + "/check-ins/item?date=" + date
                + "&category=" + CATEGORY_DIET, token);
    }

    /** 当前连续打卡天数（读生产接口，不自己算）。 */
    private int streakDays(String token, long petId) {
        ApiClient.ApiCall streak = api.get("/api/v1/app/pets/" + petId + "/check-ins/streak", token);
        assertCodeOk(streak, "连续天数");
        return streak.data().path("streak_days").asInt();
    }

    /** 这个用户名下的券（带模板编码，便于断言发的是哪一张）。 */
    private List<Map<String, Object>> coupons(long userId) {
        return jdbc.queryForList("SELECT c.`source`, c.`source_ref`, c.`status`, t.`code` AS `template_code` "
                + "FROM `coupon` c JOIN `coupon_template` t ON t.`id` = c.`template_id` "
                + "WHERE c.`user_id` = ? ORDER BY c.`id`", userId);
    }

    private long userIdOf(String token) {
        return api.get("/api/v1/app/users/me", token).data().path("id").asLong();
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
