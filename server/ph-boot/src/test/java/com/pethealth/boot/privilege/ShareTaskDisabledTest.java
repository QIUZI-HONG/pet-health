package com.pethealth.boot.privilege;

import com.fasterxml.jackson.databind.JsonNode;
import com.pethealth.boot.support.ApiClient;
import com.pethealth.boot.support.RepoRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「分享给好友」下线（V41）+ 停用项不下发的口径。
 *
 * <p>为什么要盯这一条：桌面 Web 上分享没有服务端可观测的事件，所以 {@code DAILY_SHARE} 的进度
 * 恒为 0/1——**一条永远做不完的任务比没有这条任务更糟**（用户以为坏了、运营以为埋点丢了）。
 * 停用本身是一行数据，正确性全在「C 端列表真的不下发它」这一句上：任务行还在表里，
 * 少一个过滤条件它就照旧出现在用户面前。
 *
 * <p>三个断言各管一件事，缺一条都会留下一种假绿：
 *
 * <ul>
 *   <li>迁移确实把两处都停用了（任务 + 行为：只停一处，将来有人接上报就静默开始计数）；
 *   <li>C 端任务列表里没有它，而**其余任务照常在**（「列表空了」不是通过）；
 *   <li>过滤条件真的是 {@code status}：运营把它启用回来，它立刻出现在列表里——
 *       这一条同时排除了「把 {@code DAILY_SHARE} 这个码写死排除」那种实现
 *       （那会让「停用项不下发」这条通用口径在别处失效）。
 * </ul>
 *
 * <p>与 {@code FirstBatchGrowthConfigTest} 同一套办法：**在用例里重新执行仓库里那份迁移文件**，
 * 因为基类每个用例前都会清空券模板（那是为了让每个用例自己造数据），而 V41 的那条种子
 * 引用 V39 建的 {@code CP-101}——所以两份按顺序重放，断言的对象始终是文件本身。
 */
@DisplayName("分享任务停用（V41）：停用项不下发，运营后台仍看得见")
class ShareTaskDisabledTest extends PrivilegeTestSupport {

    /** V41（本轮）：停用「分享」+ 建打卡得券档位。 */
    private static final Path V41 = Path.of(
            "server/ph-boot/src/main/resources/db/migration/V41__task_center_and_check_in_reward.sql");

    /** V39：建 `CP-101`——V41 的种子按 code 引用它，缺了它 V41 会插入失败（这是刻意的）。 */
    private static final Path V39 = Path.of(
            "server/ph-boot/src/main/resources/db/migration/V39__first_batch_growth_config.sql");

    @BeforeEach
    void replayMigrations() throws IOException {
        // 先删表：V41 里有 CREATE TABLE，重放它必须先让表不存在（顺带验了那条 DDL 能跑）
        jdbc.execute("DROP TABLE IF EXISTS `check_in_reward_rule`");
        jdbc.execute("DELETE FROM `coupon_template` WHERE `code` IN ('CP-101', 'CP-102')");
        for (String statement : statementsOf(RepoRoot.find().resolve(V39))) {
            jdbc.execute(statement);
        }
        for (String statement : statementsOf(RepoRoot.find().resolve(V41))) {
            jdbc.execute(statement);
        }
    }

    @AfterEach
    void restoreShareTask() {
        // 下面那个用例会经运营接口把「分享」启用回来（为了证明过滤条件是 status）。
        // 还原成迁移里的停用状态——共享容器里下一个测试类看到的应该是迁移跑完的样子。
        jdbc.execute("UPDATE `point_task` SET `status` = 0 WHERE `code` = 'DAILY_SHARE'");
        jdbc.execute("UPDATE `point_behavior` SET `status` = 0 WHERE `code` = 'SHARE'");
    }

    @Test
    @DisplayName("迁移停用两处：任务与行为（只停任务的话，将来有人接上报就静默开始计数）")
    void migrationDisablesBothTaskAndBehavior() {
        assertThat(jdbc.queryForObject("SELECT `status` FROM `point_task` WHERE `code` = 'DAILY_SHARE'",
                Integer.class)).as("任务停用").isZero();
        assertThat(jdbc.queryForObject("SELECT `status` FROM `point_behavior` WHERE `code` = 'SHARE'",
                Integer.class)).as("行为也停用").isZero();
        // 分值本来就是 0（V27 的编码：0 分 = 记行为不发分）——停用不是靠改分值实现的
        assertThat(jdbc.queryForObject("SELECT `points` FROM `point_behavior` WHERE `code` = 'SHARE'",
                Integer.class)).isZero();
    }

    @Test
    @DisplayName("C 端任务列表不含「分享给好友」，其余每日任务照常在；后台列表仍看得见它（status=0）")
    void appTaskListHidesDisabledTask() {
        String token = appUserToken();

        List<String> codes = appTaskCodes(token);
        assertThat(codes).as("停用项不下发").doesNotContain("DAILY_SHARE");
        // 反空转：列表不是空的、也不是被整体过滤掉了
        assertThat(codes).as("其余任务照常下发").contains("DAILY_SIGN_IN", "DAILY_CHECK_IN", "DAILY_AI_ADVICE");
        assertThat(codes).as("每日任务由六条变成五条（本轮停用了一条）").hasSize(5);

        // 「行为分值」表同理：停用的行为不再下发（给一条「0 分 · 每日 1 次」的分享只会让人以为
        // 分享还能做、只是不值分）。查名字的那张表不过滤，所以这里只断言下发的清单
        assertThat(appBehaviorCodes(token)).as("停用的行为也不下发").doesNotContain("SHARE")
                .contains("SIGN_IN", "CHECK_IN", "AI_ADVICE");

        // 运营后台看得见它——停用不等于删除（运营要能查明「这条任务为什么不见了」）
        JsonNode adminTasks = api.get("/api/v1/admin/points/tasks", adminToken()).data();
        JsonNode share = null;
        for (JsonNode task : adminTasks) {
            if ("DAILY_SHARE".equals(task.path("code").asText())) {
                share = task;
            }
        }
        assertThat(share).as("后台列表里还在").isNotNull();
        assertThat(share.path("status").asInt()).as("标着停用").isZero();
    }

    @Test
    @DisplayName("过滤条件是 status：运营启用回来，它立刻出现在 C 端列表里（不是把这个码写死排除）")
    void enableItAndItComesBack() {
        String token = appUserToken();
        assertThat(appTaskCodes(token)).doesNotContain("DAILY_SHARE");

        String admin = adminToken();
        long shareTaskId = jdbc.queryForObject("SELECT `id` FROM `point_task` WHERE `code` = 'DAILY_SHARE'",
                Long.class);
        assertCodeOk(api.put("/api/v1/admin/points/tasks/" + shareTaskId, Map.of(
                "code", "DAILY_SHARE", "name", "分享给好友", "period", 1, "behavior_code", "SHARE",
                "target_count", 1, "sort_order", 4, "status", 1), admin), "运营启用分享任务");

        assertThat(appTaskCodes(token)).as("启用后立即可见——下发的判据是 status，不是任务码白名单")
                .contains("DAILY_SHARE");
    }

    // ---------------------------------------------------------------- 工具

    /** 注册一个 C 端账号并返回访问令牌。 */
    private String appUserToken() {
        ApiClient.ApiCall registered = api.register(nextPhone());
        assertCodeOk(registered, "注册 C 端账号");
        return registered.data().path("access_token").asText();
    }

    /** C 端积分中心下发的任务码清单。 */
    private List<String> appTaskCodes(String token) {
        List<String> codes = new ArrayList<>();
        for (JsonNode task : pointsCenter(token).path("tasks")) {
            codes.add(task.path("code").asText());
        }
        return codes;
    }

    /** C 端积分中心下发的行为分值表里的行为码清单。 */
    private List<String> appBehaviorCodes(String token) {
        List<String> codes = new ArrayList<>();
        for (JsonNode behavior : pointsCenter(token).path("behaviors")) {
            codes.add(behavior.path("code").asText());
        }
        return codes;
    }

    private JsonNode pointsCenter(String token) {
        ApiClient.ApiCall center = api.get("/api/v1/app/points", token);
        assertCodeOk(center, "积分中心");
        return center.data();
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
