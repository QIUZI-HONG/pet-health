package com.pethealth.boot.record;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethealth.api.app.CheckInItemInput;
import com.pethealth.api.app.CheckInSubmitRequest;
import com.pethealth.boot.support.ApiClient;
import com.pethealth.boot.support.IntegrationTestBase;
import com.pethealth.common.time.AppTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 打卡与健康评分（切片 #97，规则与算法见 ADR-0018）。
 *
 * <p>这个类的用例逐条对应 ADR 里的承诺，改算法前先看这里会红多少条：
 * 幂等、补录窗口、连续天数（含今天未打卡不算断签）、评分公式、防疫/老年的「不计入」口径、越权。
 */
class CheckInAndScoreTest extends IntegrationTestBase {

    private ApiClient api;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUpClient() {
        api = new ApiClient(rest, objectMapper);
    }

    // ---------------------------------------------------------------- 打卡

    @Test
    @DisplayName("提交一项即算当日已打卡（不要求凑齐六项）")
    void oneItemCountsAsCheckedIn() {
        String token = register("13500000001");
        long petId = api.createPet(token, "豆豆");

        ApiClient.ApiCall day = submit(token, petId, today(), item(1, false, "12.50", null));

        assertThat(day.code()).isZero();
        assertThat(day.data().path("done").asBoolean()).isTrue();
        assertThat(day.data().path("completed_count").asInt()).isEqualTo(1);
        assertThat(day.data().path("total_count").asInt()).isEqualTo(6);
    }

    @Test
    @DisplayName("重复提交同一分项是更新而不是新增（幂等）")
    void resubmitSameCategoryUpdates() {
        String token = register("13500000002");
        long petId = api.createPet(token, "豆豆");

        submit(token, petId, today(), item(2, false, "normal", null));
        submit(token, petId, today(), item(2, true, "low", "吃得很少"));

        ApiClient.ApiCall day = getDay(token, petId, today());
        assertThat(day.data().path("completed_count").asInt()).isEqualTo(1);   // 仍然只有一项
        assertThat(day.data().path("items").get(1).path("abnormal").asBoolean()).isTrue();
        assertThat(day.data().path("items").get(1).path("note").asText()).isEqualTo("吃得很少");

        // 库里也只有一行
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM archive_record WHERE pet_id = ? AND category = 2 AND is_deleted = 0",
                Integer.class, petId)).isEqualTo(1);
    }

    @Test
    @DisplayName("一次提交六项（前端「全部正常」按钮）")
    void submitAllSix() {
        String token = register("13500000003");
        long petId = api.createPet(token, "豆豆");

        ApiClient.ApiCall day = api.post("/api/v1/app/pets/" + petId + "/check-ins",
                new CheckInSubmitRequest(today().toString(),
                        List.of(item(1, false, "12.50", null), item(2, false, "normal", null),
                                item(3, false, "normal", null), item(4, false, "normal", null),
                                item(5, false, "calm", null), item(6, false, "normal", null))),
                token);

        assertThat(day.data().path("completed_count").asInt()).isEqualTo(6);
        assertThat(day.data().path("done").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("补录：过去 7 天内可以，第 8 天被拒，未来的日期被拒")
    void backfillWindow() {
        String token = register("13500000004");
        long petId = api.createPet(token, "豆豆");

        ApiClient.ApiCall sevenDaysAgo = submit(token, petId, today().minusDays(7),
                item(1, false, "12.00", null));
        assertThat(sevenDaysAgo.code()).isZero();

        ApiClient.ApiCall eighth = submit(token, petId, today().minusDays(8), item(1, false, "12.00", null));
        assertThat(eighth.status()).isEqualTo(400);
        assertThat(eighth.code()).isEqualTo(40001);
        assertThat(eighth.message()).contains("补录");

        ApiClient.ApiCall future = submit(token, petId, today().plusDays(1), item(1, false, "12.00", null));
        assertThat(future.status()).isEqualTo(400);

        // 补录的记录带 backfilled 标记，不冒充当场录入（ADR-0018）
        ApiClient.ApiCall backfilled = getDay(token, petId, today().minusDays(7));
        assertThat(backfilled.data().path("backfilled").asBoolean()).isTrue();
        assertThat(backfilled.data().path("items").get(0).path("backfilled").asBoolean()).isTrue();

        ApiClient.ApiCall todayCall = getDay(token, petId, today());
        assertThat(todayCall.data().path("backfilled").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("体重不合法一律 40001：abc / 0 / -5 / 99999 / 三位小数（测试报告 D1）")
    void weightValueIsValidated() {
        String token = register("13500000031");
        long petId = api.createPet(token, "豆豆");

        for (String bad : List.of("abc", "0", "-5", "99999", "8.256", "１２", "1e2")) {
            ApiClient.ApiCall call = submit(token, petId, today(), item(1, false, bad, null));
            assertThat(call.status()).as("体重「%s」应被拒", bad).isEqualTo(400);
            assertThat(call.code()).as("体重「%s」应返回 40001", bad).isEqualTo(40001);
            assertThat(call.message()).contains("体重");
        }
        // 一条都没落库：校验发生在写之前
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM archive_record WHERE pet_id = ? AND category = 1", Integer.class, petId))
                .isZero();

        // 合法值照常：建档与打卡同一口径（0.01–999.99，最多两位小数）
        assertThat(submit(token, petId, today(), item(1, false, "8.25", null)).code()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT numeric_value FROM archive_record WHERE pet_id = ? AND category = 1",
                BigDecimal.class, petId)).isEqualByComparingTo("8.25");

        // 非体重分项的线值不受影响（normal / low / high 照旧）
        assertThat(submit(token, petId, today(), item(2, false, "low", null)).code()).isZero();

        // 体重**留空**是合法意图（「这一项先不记」），不是填错：它不该被拦
        String other = register("13500000035");
        long otherPet = api.createPet(other, "豆豆");
        assertThat(submit(other, otherPet, today(), item(1, false, null, null)).code()).isZero();
    }

    @Test
    @DisplayName("撤销后再提交同一分项：复活那一行，不再撞唯一键（测试报告 D2）")
    void resubmitAfterUndoRevivesTheRow() {
        String token = register("13500000032");
        long petId = api.createPet(token, "豆豆");

        assertThat(submit(token, petId, today(), item(2, false, "normal", null)).code()).isZero();
        String undoUrl = "/api/v1/app/pets/" + petId + "/check-ins/item?date=" + today() + "&category=2";
        assertThat(api.delete(undoUrl, token).code()).isZero();

        // 「填错 → 撤销 → 重新填」是必然路径：撤销是逻辑删除，uk_checkin_slot 仍占着那一行，
        // 早先会撞唯一键报 50000（已复现）。修法是在同一槽上复活，而不是新插一行。
        ApiClient.ApiCall again = submit(token, petId, today(), item(2, true, "low", "有点软"));
        assertThat(again.status()).as(again.body().toPrettyString()).isEqualTo(200);
        assertThat(again.code()).isZero();
        assertThat(again.data().path("completed_count").asInt()).isEqualTo(1);

        // 只有一行活记录，且内容是新的
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT content, abnormal, is_deleted FROM archive_record WHERE pet_id = ? AND category = 2", petId);
        assertThat(row.get("is_deleted")).isEqualTo(0);
        assertThat(row.get("abnormal")).isEqualTo(1);
        assertThat(row.get("content").toString()).contains("有点软");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM archive_record WHERE pet_id = ? AND category = 2", Integer.class, petId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("格式对但不存在的日期：40001，不是 50000（测试报告 D11）")
    void impossibleDatesAreRejectedAsParamInvalid() {
        String token = register("13500000033");
        long petId = api.createPet(token, "豆豆");

        // 注意：日期要用**字符串**传（`LocalDate.of(2026, 2, 31)` 在 Java 侧同样抛异常，
        // 那样测的是测试代码自己）。契约里 date 就是字符串，用户传得进来的正是这种值。
        ApiClient.ApiCall call = api.post("/api/v1/app/pets/" + petId + "/check-ins",
                new CheckInSubmitRequest("2026-02-31", List.of(item(2, false, "normal", null))), token);

        assertThat(call.status()).isEqualTo(400);
        assertThat(call.code()).isEqualTo(40001);
        assertThat(call.message()).contains("日期");
    }

    @Test
    @DisplayName("同一宠物同一天并发提交：不报 500（当日评分行的唯一键冲突被接住，测试报告 D19）")
    void concurrentSubmitsOnSameDayDoNotFail() throws Exception {
        String token = register("13500000034");
        long petId = api.createPet(token, "豆豆");
        int threads = 4;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.List<Integer> codes = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        try {
            for (int i = 0; i < threads; i++) {
                // 第 1 项是体重：它的取值必须是数字（「填 normal」现在会被 400 拦下，这正是 D1 的修复）
                int category = i + 1;
                String value = category == 1 ? "8.20" : "normal";
                pool.submit(() -> {
                    try {
                        start.await();
                        // 两个并发写会同时算当天的 health_score：查不到就插 → 撞 uk_pet_calc_date，
                        // 早先是未捕获的 DuplicateKeyException（50000）
                        codes.add(submit(token, petId, today(), item(category, false, value, null)).status());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(codes).hasSize(threads).allMatch(status -> status == 200);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM health_score WHERE pet_id = ? AND calc_date = ?",
                Integer.class, petId, today())).isEqualTo(1);
    }

    @Test
    @DisplayName("撤销：填错了能撤，重复撤销也返回成功（幂等）")
    void undoIsIdempotent() {
        String token = register("13500000005");
        long petId = api.createPet(token, "豆豆");
        submit(token, petId, today(), item(1, false, "12.50", null));

        String url = "/api/v1/app/pets/" + petId + "/check-ins/item?date=" + today() + "&category=1";
        assertThat(api.delete(url, token).data().path("completed_count").asInt()).isZero();
        assertThat(api.delete(url, token).data().path("completed_count").asInt()).isZero();   // 再撤一次也不报错
        // 逻辑删除：行还在（ADR-0011「物理删除仅限账号注销」）
        assertThat(jdbc.queryForObject("SELECT is_deleted FROM archive_record WHERE pet_id = ?", Integer.class, petId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("没填的日期返回空态而不是报错")
    void emptyDayIsNotAnError() {
        String token = register("13500000006");
        long petId = api.createPet(token, "豆豆");

        ApiClient.ApiCall day = getDay(token, petId, today());

        assertThat(day.code()).isZero();
        assertThat(day.data().path("done").asBoolean()).isFalse();
        assertThat(day.data().path("completed_count").asInt()).isZero();
        assertThat(day.data().path("items")).hasSize(6);
    }

    @Test
    @DisplayName("别人的宠物：打卡读、写、撤销、评分一律 40400")
    void otherUsersPetIsInvisible() {
        String ownerToken = register("13500000007");
        long petId = api.createPet(ownerToken, "豆豆");
        String intruderToken = register("13500000008");

        assertThat(getDay(intruderToken, petId, today()).code()).isEqualTo(40400);
        assertThat(submit(intruderToken, petId, today(), item(1, false, "12.00", null)).code()).isEqualTo(40400);
        assertThat(api.delete("/api/v1/app/pets/" + petId + "/check-ins/item?date=" + today() + "&category=1",
                intruderToken).code()).isEqualTo(40400);
        assertThat(api.get("/api/v1/app/pets/" + petId + "/health-score", intruderToken).code()).isEqualTo(40400);
        assertThat(api.get("/api/v1/app/pets/" + petId + "/check-ins/streak", intruderToken).code()).isEqualTo(40400);
    }

    // ---------------------------------------------------------------- 连续天数

    @Test
    @DisplayName("连续天数：昨天+今天连续；断签归零；今天没打卡不算断签")
    void streakRules() {
        String token = register("13500000009");
        long petId = api.createPet(token, "豆豆");

        // 前天、昨天各一条，今天还没打卡 → 连续 2 天（今天未打卡不算断签）
        submit(token, petId, today().minusDays(2), item(6, false, "normal", null));
        submit(token, petId, today().minusDays(1), item(6, false, "normal", null));

        ApiClient.ApiCall streak = api.get("/api/v1/app/pets/" + petId + "/check-ins/streak", token);
        assertThat(streak.data().path("streak_days").asInt()).isEqualTo(2);
        assertThat(streak.data().path("checked_today").asBoolean()).isFalse();

        // 今天补上 → 连续 3 天
        submit(token, petId, today(), item(6, false, "normal", null));
        streak = api.get("/api/v1/app/pets/" + petId + "/check-ins/streak", token);
        assertThat(streak.data().path("streak_days").asInt()).isEqualTo(3);
        assertThat(streak.data().path("checked_today").asBoolean()).isTrue();

        // 中间断一天：把「前天」那条删掉，连续数立刻从昨天算起 → 1 天
        api.delete("/api/v1/app/pets/" + petId + "/check-ins/item?date=" + today().minusDays(2) + "&category=6",
                token);
        streak = api.get("/api/v1/app/pets/" + petId + "/check-ins/streak", token);
        assertThat(streak.data().path("streak_days").asInt()).isEqualTo(2);   // 昨天 + 今天
        // 最长连续段是**按现有记录算**的，不是历史峰值：删掉那天，那段也就不存在了。
        // 要「历史峰值」得单独存一张表——等 #113 权益真正要按连续天数发奖时再做（ADR-0018）。
        assertThat(streak.data().path("longest_streak_days").asInt()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- 评分算法

    @Test
    @DisplayName("评分公式：70×完整度 + 30×(1−0.1×异常)，总分是已计入维度的等权平均")
    void scoreFollowsTheDocumentedFormula() {
        String token = register("13500000010");
        // 2 岁、无慢病 → 老年专项不计入；无疫苗记录 → 防疫不计入
        long petId = api.createPetDetailed(token, "豆豆", LocalDate.now().minusYears(2), false);

        // 近 7 天里记 4 天（今天、-1、-2、-3），其中 -3 的排泄标记异常
        submit(token, petId, today(), item(1, false, "12.50", null));
        submit(token, petId, today().minusDays(1), item(1, false, "12.40", null));
        submit(token, petId, today().minusDays(2), item(1, false, "12.30", null));
        submit(token, petId, today().minusDays(3), item(3, true, "loose", "有点软"));

        ApiClient.ApiCall score = api.get("/api/v1/app/pets/" + petId + "/health-score", token);
        var dimensions = score.data().path("dimensions");

        // 生理：4 天完整度 = 70×4/7 = 40，异常 1 次 = 30×(1−0.1) = 27 → 67
        assertThat(dimension(dimensions, "physiology").path("score").asInt()).isEqualTo(67);
        // 行为：0 天 → 0×70 + 30 = 30（没记录也不是 0 分，异常才是扣分项）
        assertThat(dimension(dimensions, "behavior").path("score").asInt()).isEqualTo(30);
        // 卫生：同上 → 30
        assertThat(dimension(dimensions, "hygiene").path("score").asInt()).isEqualTo(30);
        // 总分 = (67+30+30)/3 = 42
        assertThat(score.data().path("total_score").asInt()).isEqualTo(42);
        assertThat(score.data().path("grade").asText()).isEqualTo("需关注");
    }

    @Test
    @DisplayName("防疫无数据时显示「待录入」且不计入总分；老年未开启时显示「未开启」")
    void excludedDimensionsAreNotCounted() {
        String token = register("13500000011");
        long petId = api.createPetDetailed(token, "豆豆", LocalDate.now().minusYears(2), false);
        submit(token, petId, today(), item(1, false, "12.50", null));

        ApiClient.ApiCall score = api.get("/api/v1/app/pets/" + petId + "/health-score", token);
        var epidemic = dimension(score.data().path("dimensions"), "epidemic");
        var elderly = dimension(score.data().path("dimensions"), "elderly");

        assertThat(epidemic.path("included").asBoolean()).isFalse();
        assertThat(epidemic.path("excluded_reason").asText()).isEqualTo("待录入");
        assertThat(epidemic.path("score").isNull()).isTrue();
        assertThat(elderly.path("excluded_reason").asText()).isEqualTo("未开启");

        // 总分只由计入的三维平均（生理 70×1/7+30=40；行为/卫生 30）
        assertThat(score.data().path("total_score").asInt()).isEqualTo(33);
    }

    @Test
    @DisplayName("7 岁以上或有慢病 → 老年专项开启并计入总分")
    void elderlyDimensionActivates() {
        String token = register("13500000012");
        long oldPetId = api.createPetDetailed(token, "老狗", LocalDate.now().minusYears(9), false);
        long chronicPetId = api.createPetDetailed(token, "病猫", LocalDate.now().minusYears(2), true);

        submit(token, oldPetId, today(), item(1, false, "10.00", null));
        submit(token, chronicPetId, today(), item(1, false, "4.00", null));

        var oldElderly = dimension(api.get("/api/v1/app/pets/" + oldPetId + "/health-score", token)
                .data().path("dimensions"), "elderly");
        var chronicElderly = dimension(api.get("/api/v1/app/pets/" + chronicPetId + "/health-score", token)
                .data().path("dimensions"), "elderly");

        assertThat(oldElderly.path("included").asBoolean()).isTrue();      // 9 岁
        assertThat(chronicElderly.path("included").asBoolean()).isTrue();  // 慢病触发
    }

    @Test
    @DisplayName("一条记录都没有：total_score 为 null（前端显示「还没有评分」，不是 0 分）")
    void noRecordsMeansNoScore() {
        String token = register("13500000013");
        long petId = api.createPet(token, "豆豆");

        ApiClient.ApiCall score = api.get("/api/v1/app/pets/" + petId + "/health-score", token);

        assertThat(score.code()).isZero();
        assertThat(score.data().path("total_score").isNull()).isTrue();
        assertThat(score.data().path("grade").asText()).isEqualTo("暂无数据");
        assertThat(score.data().path("disclaimer").asText()).contains("不能替代兽医诊断");
    }

    @Test
    @DisplayName("打卡后评分即时重算并落库（F005 要求「有反馈」）")
    void scoreIsPersistedOnCheckIn() {
        String token = register("13500000014");
        long petId = api.createPet(token, "豆豆");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM health_score WHERE pet_id = ?", Integer.class, petId))
                .isZero();

        submit(token, petId, today(), item(1, false, "12.50", null));

        assertThat(jdbc.queryForObject(
                "SELECT total_score FROM health_score WHERE pet_id = ? AND calc_date = ?",
                Integer.class, petId, today())).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT included_dimensions FROM health_score WHERE pet_id = ? AND calc_date = ?",
                Integer.class, petId, today())).isEqualTo(3);   // 生理/行为/卫生（防疫待录入、老年未开启）
    }

    @Test
    @DisplayName("趋势：近 7 天有评分的天才出现")
    void trendContainsOnlyScoredDays() {
        String token = register("13500000015");
        long petId = api.createPet(token, "豆豆");
        submit(token, petId, today().minusDays(1), item(1, false, "12.00", null));
        submit(token, petId, today(), item(1, false, "12.50", null));

        ApiClient.ApiCall score = api.get("/api/v1/app/pets/" + petId + "/health-score", token);

        assertThat(score.data().path("trend")).hasSize(2);
        assertThat(score.data().path("trend").get(0).path("date").asText())
                .isEqualTo(today().minusDays(1).toString());
    }

    @Test
    @DisplayName("分项编号只能是 1–6（7 防疫不是打卡项）")
    void categoryMustBeCheckInCategory() {
        String token = register("13500000016");
        long petId = api.createPet(token, "豆豆");

        ApiClient.ApiCall call = submit(token, petId, today(), item(7, false, null, null));

        assertThat(call.status()).isEqualTo(400);
        assertThat(call.code()).isEqualTo(40001);
    }

    // ---------------------------------------------------------------- 工具

    private LocalDate today() {
        return AppTime.today();
    }

    private CheckInItemInput item(int category, boolean abnormal, String value, String note) {
        return new CheckInItemInput(category, abnormal, value, note);
    }

    private ApiClient.ApiCall submit(String token, long petId, LocalDate date, CheckInItemInput item) {
        return api.post("/api/v1/app/pets/" + petId + "/check-ins",
                new CheckInSubmitRequest(date.toString(), List.of(item)), token);
    }

    private ApiClient.ApiCall getDay(String token, long petId, LocalDate date) {
        return api.get("/api/v1/app/pets/" + petId + "/check-ins?date=" + date, token);
    }

    private com.fasterxml.jackson.databind.JsonNode dimension(com.fasterxml.jackson.databind.JsonNode dimensions,
                                                             String key) {
        for (com.fasterxml.jackson.databind.JsonNode node : dimensions) {
            if (key.equals(node.path("key").asText())) {
                return node;
            }
        }
        throw new AssertionError("找不到维度：" + key + "，实际内容：" + dimensions);
    }

    private String register(String phone) {
        return api.registerAndGetAccessToken(phone);
    }
}
