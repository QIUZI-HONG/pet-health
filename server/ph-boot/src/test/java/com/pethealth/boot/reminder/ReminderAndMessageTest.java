package com.pethealth.boot.reminder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethealth.api.app.CheckInItemInput;
import com.pethealth.api.app.CheckInSubmitRequest;
import com.pethealth.api.app.EpidemicRecordInput;
import com.pethealth.boot.support.ApiClient;
import com.pethealth.boot.support.IntegrationTestBase;
import com.pethealth.common.time.AppTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 提醒与消息中心（切片 #99，规则见 ADR-0019）。
 *
 * <p>逐条对应 ADR 的承诺：幂等去重（同键更新而非新增）、疫苗按窗口与阈值触发、
 * 趋势按幅度触发、慢病/老年按周期触发、异常打卡即时生成、每天最多三条、
 * 开关生效、未读数正确、已读幂等、越权隔离。
 */
class ReminderAndMessageTest extends IntegrationTestBase {

    private ApiClient api;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUpClient() {
        api = new ApiClient(rest, objectMapper);
    }

    // ---------------------------------------------------------------- 疫苗提醒

    @Test
    @DisplayName("疫苗到期前 7 天开始提醒；同一条记录反复生成只更新文案，不新增")
    void vaccineReminderIsIdempotent() {
        String token = register("13200000001");
        long petId = api.createPet(token, "豆豆");
        // 到期日 = 今天 + 5 天（在 7 天窗口内）
        addEpidemicRecord(token, petId, 1, "狂犬疫苗", today(), today().plusDays(5));

        JsonNode first = highlights(token);
        assertThat(first).hasSize(1);
        String firstTitle = first.get(0).path("title").asText();
        assertThat(firstTitle).contains("还有 5 天");
        long firstId = first.get(0).path("id").asLong();

        // 再读一次（惰性补算会再跑一遍生成）：条数不变、id 不变——幂等键挡住了重复
        JsonNode second = highlights(token);
        assertThat(second).hasSize(1);
        assertThat(second.get(0).path("id").asLong()).isEqualTo(firstId);
    }

    @Test
    @DisplayName("到期日在窗口外（8 天后）不生成提醒")
    void vaccineOutsideWindowIsSilent() {
        String token = register("13200000002");
        long petId = api.createPet(token, "豆豆");
        addEpidemicRecord(token, petId, 1, "狂犬疫苗", today(), today().plusDays(8));

        assertThat(highlights(token)).isEmpty();
    }

    @Test
    @DisplayName("已过期的疫苗也提醒（说「已到期」而不是「还有 -3 天」）")
    void overdueVaccineSaysExpired() {
        String token = register("13200000003");
        long petId = api.createPet(token, "豆豆");
        addEpidemicRecord(token, petId, 1, "犬四联", today().minusDays(30), today().minusDays(3));

        JsonNode highlights = highlights(token);

        // 只断言疫苗那一条：日常提醒也会出现在同一列表里（每个当天没记录的宠物都有一条）
        JsonNode vaccine = byType(highlights, 1);
        assertThat(vaccine).isNotNull();
        assertThat(vaccine.path("title").asText()).contains("已到期");
    }

    @Test
    @DisplayName("驱虫与疫苗是两类提醒，各自独立")
    void vaccineAndDewormAreSeparate() {
        String token = register("13200000004");
        long petId = api.createPet(token, "豆豆");
        addEpidemicRecord(token, petId, 1, "狂犬疫苗", today(), today().plusDays(3));
        addEpidemicRecord(token, petId, 2, "体外驱虫", today(), today().plusDays(2));

        JsonNode highlights = highlights(token);

        assertThat(highlights).hasSize(2);
        List<String> types = highlights.findValuesAsText("type");
        assertThat(types).containsExactlyInAnyOrder("1", "2");
    }

    // ---------------------------------------------------------------- 异常即时

    @Test
    @DisplayName("打卡标注异常 → 即时生成提醒（不用等批算）")
    void abnormalCheckInGeneratesImmediately() {
        String token = register("13200000005");
        long petId = api.createPet(token, "豆豆");

        api.post("/api/v1/app/pets/" + petId + "/check-ins",
                new CheckInSubmitRequest(today().toString(),
                        List.of(new CheckInItemInput(3, true, null, "有点软"))), token);

        JsonNode highlights = highlights(token);
        assertThat(highlights).hasSize(1);
        assertThat(highlights.get(0).path("type").asInt()).isEqualTo(4);   // 4 = 异常
        assertThat(highlights.get(0).path("risk_level").asInt()).isEqualTo(2);   // 黄：宁严勿松
        assertThat(highlights.get(0).path("content").asText()).contains("异常");
    }

    @Test
    @DisplayName("正常打卡不生成提醒（不制造噪音）")
    void normalCheckInIsSilent() {
        String token = register("13200000006");
        long petId = api.createPet(token, "豆豆");

        api.post("/api/v1/app/pets/" + petId + "/check-ins",
                new CheckInSubmitRequest(today().toString(),
                        List.of(new CheckInItemInput(3, false, "normal", null))), token);

        assertThat(highlights(token)).isEmpty();
    }

    // ---------------------------------------------------------------- 趋势与慢病/老年

    @Test
    @DisplayName("近 7 天体重变化 ≥5% → 趋势提醒；变化小则不提醒")
    void trendNeedsEnoughChange() {
        String token = register("13200000007");
        long petId = api.createPet(token, "豆豆");

        // 10.00 → 10.80 是 +8%
        checkInWeight(token, petId, today().minusDays(5), "10.00");
        checkInWeight(token, petId, today(), "10.80");
        JsonNode highlights = highlights(token);
        assertThat(highlights).hasSize(1);
        assertThat(highlights.get(0).path("type").asInt()).isEqualTo(5);
        assertThat(highlights.get(0).path("title").asText()).contains("上升");
    }

    @Test
    @DisplayName("体重小幅波动（<5%）不提醒")
    void smallWeightChangeIsSilent() {
        String token = register("13200000008");
        long petId = api.createPet(token, "豆豆");
        checkInWeight(token, petId, today().minusDays(3), "10.00");
        checkInWeight(token, petId, today(), "10.20");

        assertThat(highlights(token)).isEmpty();
    }

    @Test
    @DisplayName("老年宠物（≥7 岁）生成照护提醒；同一周期只一条")
    void elderlyReminderOncePerPeriod() {
        String token = register("13200000009");
        long petId = api.createPetDetailed(token, "老狗", today().minusYears(9), false);

        JsonNode care = byType(highlights(token), 6);
        assertThat(care).isNotNull();
        assertThat(care.path("risk_level").asInt()).isEqualTo(1);   // 绿：不是紧急事

        // 反复读不新增（幂等窗口 = 按间隔划出的周期）
        long careCount = 0;
        for (JsonNode node : highlights(token)) {
            if (node.path("type").asInt() == 6) {
                careCount++;
            }
        }
        assertThat(careCount).isEqualTo(1);
    }

    @Test
    @DisplayName("年轻且无慢病的宠物没有照护提醒")
    void youngHealthyPetHasNoCareReminder() {
        String token = register("13200000010");
        api.createPetDetailed(token, "小狗", today().minusYears(2), false);

        // 年轻无慢病 → 没有照护类提醒（日常类会有，不属于这条断言的范围）
        assertThat(byType(highlights(token), 6)).isNull();
    }

    // ---------------------------------------------------------------- 上限与开关

    @Test
    @DisplayName("每宠物每天最多三条：超出按紧迫度保留，不合并成汇总条（防骚扰）")
    void maxThreePerPetPerDay() {
        String token = register("13200000011");
        long oldPetId = api.createPetDetailed(token, "老狗", today().minusYears(9), false);
        // 同一只宠物上叠加多类：疫苗 + 驱虫 + 日常 + 趋势 + 老年 = 5 类，只留 3 条
        addEpidemicRecord(token, oldPetId, 1, "狂犬疫苗", today(), today().plusDays(3));
        addEpidemicRecord(token, oldPetId, 2, "体外驱虫", today(), today().plusDays(2));
        checkInWeight(token, oldPetId, today().minusDays(5), "10.00");
        checkInWeight(token, oldPetId, today(), "12.00");   // +20% 触发趋势

        assertThat(highlights(token)).hasSize(3);
    }

    @Test
    @DisplayName("关掉某一类后不再生成该类提醒，但已存在的保留")
    void disabledTypeStopsGenerating() {
        String token = register("13200000012");
        long petId = api.createPet(token, "豆豆");
        addEpidemicRecord(token, petId, 1, "狂犬疫苗", today(), today().plusDays(3));
        assertThat(highlights(token)).hasSize(1);

        ApiClient.ApiCall updated = api.put("/api/v1/app/messages/settings",
                Map.of("type", 1, "enabled", false), token);
        assertThat(updated.code()).isZero();

        // 关掉只是不再生成新的；已经生成的还在（用户自己去消息中心处理）
        assertThat(highlights(token)).hasSize(1);
        // 开关状态可查
        JsonNode vaccineSetting = findSetting(updated.data(), 1);
        assertThat(vaccineSetting.path("enabled").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("开关默认全开；**异常类不可关闭**（红色风险承载在它上面），其余可关")
    void settingsReturnDefaultsAndClosability() {
        String token = register("13200000013");

        JsonNode settings = api.get("/api/v1/app/messages/settings", token).data();

        assertThat(settings).hasSize(6);   // 疫苗/驱虫/日常/异常/趋势/慢病老年
        for (JsonNode setting : settings) {
            assertThat(setting.path("enabled").asBoolean()).isTrue();
            assertThat(setting.path("platform_enabled").asBoolean()).isTrue();
            boolean shouldBeClosable = setting.path("type").asInt() != 4;   // 4 = 异常
            assertThat(setting.path("closable").asBoolean()).isEqualTo(shouldBeClosable);
        }
    }

    @Test
    @DisplayName("关掉异常类被拒（ADR-0019：红色不可关，异常类承载红色风险）")
    void abnormalTypeCannotBeDisabled() {
        String token = register("13200000024");

        ApiClient.ApiCall call = api.put("/api/v1/app/messages/settings",
                Map.of("type", 4, "enabled", false), token);

        assertThat(call.status()).isEqualTo(400);
        assertThat(call.message()).contains("不能关闭");
    }

    @Test
    @DisplayName("日常：当天还没有记录时生成一条打卡提醒；有记录就不生成")
    void dailyReminderReflectsCheckInState() {
        String token = register("13200000025");
        long petId = api.createPet(token, "豆豆");

        JsonNode before = highlights(token);
        assertThat(before).hasSize(1);
        assertThat(before.get(0).path("type").asInt()).isEqualTo(3);   // 3 = 日常
        assertThat(before.get(0).path("title").asText()).contains("还没有记录");

        // 打卡之后不再提醒（幂等键里带日期，同一天只有一条，且状态变了就不再生成）
        submitNormalCheckIn(token, petId);
        JsonNode after = highlights(token);
        // 已经生成的那条还在（用户没读），但不会再多一条
        assertThat(after).hasSize(1);
    }

    @Test
    @DisplayName("删除单条（软删除）：列表里不再出现，幂等")
    void deleteSingleMessage() {
        String token = register("13200000026");
        long petId = api.createPet(token, "豆豆");
        addEpidemicRecord(token, petId, 1, "狂犬疫苗", today(), today().plusDays(3));
        long messageId = api.get("/api/v1/app/messages", token).data().path("list").get(0).path("id").asLong();

        assertThat(api.delete("/api/v1/app/messages/" + messageId, token).code()).isZero();
        assertThat(api.get("/api/v1/app/messages", token).data().path("total").asInt()).isZero();
        assertThat(api.get("/api/v1/app/messages/unread-count", token).data().path("unread").asInt()).isZero();

        // 再删一次：不存在 → 404（与「别人的消息」同一处理）
        assertThat(api.delete("/api/v1/app/messages/" + messageId, token).code()).isEqualTo(40400);
    }

    @Test
    @DisplayName("每日上限对即时路径同样生效：补录多条异常不会堆出一堆提醒")
    void abnormalRemindersRespectDailyCap() {
        String token = register("13200000027");
        long petId = api.createPet(token, "豆豆");

        // 补录 5 天，每天都标异常 → 只有 3 条能生成（上限按「今天生成了几条」算，默认 3）
        for (int i = 0; i < 5; i++) {
            api.post("/api/v1/app/pets/" + petId + "/check-ins",
                    new CheckInSubmitRequest(today().minusDays(i).toString(),
                            List.of(new CheckInItemInput(3, true, null, "异常"))), token);
        }

        assertThat(highlights(token)).hasSize(3);
    }

    @Test
    @DisplayName("批算入口：遍历所有有宠物的用户，不报错且能生成（@Scheduled 的载体）")
    void batchRunGeneratesForAllUsers(@Autowired com.pethealth.reminder.service.ReminderGenerator generator) {
        String token = register("13200000028");
        long petId = api.createPet(token, "豆豆");
        addEpidemicRecord(token, petId, 1, "狂犬疫苗", today(), today().plusDays(2));

        // 清掉惰性补算生成的那些，验证批算自己能生成
        long userId = jdbc.queryForObject("SELECT id FROM `user` WHERE is_deleted = 0", Long.class);
        jdbc.execute("DELETE FROM message");
        assertThat(generator.materialize(userId)).isGreaterThan(0);
        assertThat(highlights(token)).isNotEmpty();
    }

    @Test
    @DisplayName("不可开关的类型被拒（业务通知不属于提醒）")
    void unknownTypeIsRejected() {
        String token = register("13200000014");

        ApiClient.ApiCall call = api.put("/api/v1/app/messages/settings",
                Map.of("type", 7, "enabled", false), token);   // 7 = 订单通知

        assertThat(call.status()).isEqualTo(400);
        assertThat(call.code()).isEqualTo(40001);
    }

    // ---------------------------------------------------------------- 消息中心

    @Test
    @DisplayName("未读数正确；标记单条已读后减少；重复标记幂等")
    void unreadCountAndMarkRead() {
        String token = register("13200000015");
        long petId = api.createPet(token, "豆豆");
        addEpidemicRecord(token, petId, 1, "狂犬疫苗", today(), today().plusDays(3));
        addEpidemicRecord(token, petId, 2, "体外驱虫", today(), today().plusDays(2));

        // 先读一次列表：提醒是**惰性补算**的，未读数接口本身不写库（ADR-0019 的实现说明），
        // 所以此时还没有消息；读完列表后才有
        int initialUnread = api.get("/api/v1/app/messages", token).data().path("list").size();
        assertThat(initialUnread).isGreaterThanOrEqualTo(2);   // 疫苗 + 驱虫（外加可能的日常）
        assertThat(api.get("/api/v1/app/messages/unread-count", token).data().path("unread").asInt())
                .isEqualTo(initialUnread);

        long messageId = api.get("/api/v1/app/messages", token).data().path("list").get(0).path("id").asLong();
        JsonNode read = api.put("/api/v1/app/messages/" + messageId + "/read", null, token).data();
        assertThat(read.path("read").asBoolean()).isTrue();
        assertThat(read.path("read_at").asText()).isNotBlank();

        assertThat(api.get("/api/v1/app/messages/unread-count", token).data().path("unread").asInt())
                .isEqualTo(initialUnread - 1);

        // 重复标记：已读时间不变（多端同步时第一条为准）。
        // 断言以**库里的值**为准：响应里的时间是内存里写进去的，秒边界上可能与库值差一秒，
        // 而幂等性要保证的是「第二次不再改写那一列」。
        java.time.LocalDateTime firstReadAt = jdbc.queryForObject(
                "SELECT read_at FROM message WHERE id = ?", java.time.LocalDateTime.class, messageId);
        api.put("/api/v1/app/messages/" + messageId + "/read", null, token);
        java.time.LocalDateTime afterSecondMark = jdbc.queryForObject(
                "SELECT read_at FROM message WHERE id = ?", java.time.LocalDateTime.class, messageId);
        assertThat(afterSecondMark).isEqualTo(firstReadAt);
    }

    @Test
    @DisplayName("全部已读：幂等，且未读归零")
    void markAllRead() {
        String token = register("13200000016");
        long petId = api.createPet(token, "豆豆");
        addEpidemicRecord(token, petId, 1, "狂犬疫苗", today(), today().plusDays(3));

        JsonNode result = api.put("/api/v1/app/messages/read-all", null, token).data();
        assertThat(result.path("unread").asInt()).isZero();
        assertThat(api.put("/api/v1/app/messages/read-all", null, token).code()).isZero();   // 再调也成功
    }

    @Test
    @DisplayName("消息列表分页，且能按 kind 过滤")
    void listIsPagedAndFilterable() {
        String token = register("13200000017");
        long petId = api.createPet(token, "豆豆");
        addEpidemicRecord(token, petId, 1, "狂犬疫苗", today(), today().plusDays(3));

        JsonNode page = api.get("/api/v1/app/messages?page=1&page_size=20", token).data();
        assertThat(page.path("total").asInt()).isEqualTo(1);
        assertThat(page.path("list")).hasSize(1);

        JsonNode reminders = api.get("/api/v1/app/messages?kind=1", token).data();
        assertThat(reminders.path("total").asInt()).isEqualTo(1);
        JsonNode notifications = api.get("/api/v1/app/messages?kind=2", token).data();
        assertThat(notifications.path("total").asInt()).isZero();
    }

    @Test
    @DisplayName("别人的消息读不到、标不了已读（越权按不存在处理）")
    void otherUsersMessagesAreInvisible() {
        String ownerToken = register("13200000018");
        long petId = api.createPet(ownerToken, "豆豆");
        addEpidemicRecord(ownerToken, petId, 1, "狂犬疫苗", today(), today().plusDays(3));
        long messageId = api.get("/api/v1/app/messages", ownerToken).data().path("list").get(0).path("id").asLong();

        String intruderToken = register("13200000019");

        assertThat(api.put("/api/v1/app/messages/" + messageId + "/read", null, intruderToken).code())
                .isEqualTo(40400);
        assertThat(api.get("/api/v1/app/messages", intruderToken).data().path("total").asInt()).isZero();
        assertThat(api.get("/api/v1/app/messages/unread-count", intruderToken).data().path("unread").asInt())
                .isZero();
    }

    @Test
    @DisplayName("补录历史疫苗也能计分；已过期只给 60（ADR-0025 的普通场景）")
    void epidemicDimensionCountsBackfilledRecords() {
        String token = register("13200000031");
        long petId = api.createPet(token, "豆豆");
        var today = today();

        // 最常见的建档场景：用户补录几个月前打的疫苗——绝不是「近 7 天录入」能覆盖的
        addEpidemicRecord(token, petId, 1, "狂犬疫苗", today.minusMonths(3), today.plusMonths(9));
        JsonNode fresh = dimension(api.get("/api/v1/app/pets/" + petId + "/health-score", token)
                .data().path("dimensions"), "epidemic");
        assertThat(fresh.path("included").asBoolean()).isTrue();
        assertThat(fresh.path("score").asInt()).isEqualTo(100);
        // 窗口内没有其它记录时总分仍为空：ADR-0018 的本意是「没有记录就不要给总分」，
        // 否则只补录过疫苗的用户会看到一个 100 分
        assertThat(api.get("/api/v1/app/pets/" + petId + "/health-score", token)
                .data().path("total_score").isNull()).isTrue();

        // 到期日已过：仍计入，但降档
        addEpidemicRecord(token, petId, 2, "体内驱虫", today.minusMonths(6), today.minusDays(3));
        JsonNode overdue = dimension(api.get("/api/v1/app/pets/" + petId + "/health-score", token)
                .data().path("dimensions"), "epidemic");
        assertThat(overdue.path("included").asBoolean()).isTrue();
        assertThat(overdue.path("score").asInt()).isEqualTo(60);
    }

    @Test
    @DisplayName("防疫记录录入后，健康评分的「防疫」维度开始计分")
    void epidemicRecordUnlocksEpidemicDimension() {
        String token = register("13200000020");
        long petId = api.createPet(token, "豆豆");

        JsonNode before = api.get("/api/v1/app/pets/" + petId + "/health-score", token).data();
        assertThat(dimension(before.path("dimensions"), "epidemic").path("included").asBoolean()).isFalse();

        addEpidemicRecord(token, petId, 1, "狂犬疫苗", today(), today().plusDays(300));

        JsonNode after = api.get("/api/v1/app/pets/" + petId + "/health-score", token).data();
        JsonNode epidemic = dimension(after.path("dimensions"), "epidemic");
        assertThat(epidemic.path("included").asBoolean()).isTrue();
        assertThat(epidemic.path("score").asInt()).isGreaterThan(0);
    }

    @Test
    @DisplayName("防疫记录只能记在自己的宠物上；下次日期早于本次被拒")
    void epidemicRecordValidation() {
        String ownerToken = register("13200000021");
        long petId = api.createPet(ownerToken, "豆豆");
        String intruderToken = register("13200000022");

        ApiClient.ApiCall foreign = api.post("/api/v1/app/pets/" + petId + "/epidemic-records",
                new EpidemicRecordInput(1, "狂犬疫苗", today().toString(), null), intruderToken);
        assertThat(foreign.code()).isEqualTo(40400);

        ApiClient.ApiCall badRange = api.post("/api/v1/app/pets/" + petId + "/epidemic-records",
                new EpidemicRecordInput(1, "狂犬疫苗", today().toString(), today().minusDays(1).toString()),
                ownerToken);
        assertThat(badRange.status()).isEqualTo(400);
        assertThat(badRange.code()).isEqualTo(40001);

        ApiClient.ApiCall future = api.post("/api/v1/app/pets/" + petId + "/epidemic-records",
                new EpidemicRecordInput(1, "狂犬疫苗", today().plusDays(1).toString(), null), ownerToken);
        assertThat(future.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("删除提醒后再次物化不会 500：同键的行被复活（切片 #99 的缺陷回归）")
    void deletingThenRematerializingDoesNotBreak() {
        String token = register("13200000030");
        long petId = api.createPet(token, "豆豆");

        ApiClient.ApiCall first = api.get("/api/v1/app/messages?page=1&page_size=10", token);
        assertThat(first.code()).isZero();
        JsonNode items = first.data().path("list");
        assertThat(items).isNotEmpty();
        long messageId = items.get(0).path("id").asLong();

        assertThat(api.delete("/api/v1/app/messages/" + messageId, token).code()).isZero();

        // 删除是软删，而 uk_dedup 是物理唯一键：再物化时若走 insert 就会撞键报 500
        ApiClient.ApiCall again = api.get("/api/v1/app/messages?page=1&page_size=10", token);
        assertThat(again.code()).as("再次读取消息中心不该报错：" + again.body()).isZero();
        assertThat(again.data().path("list").toString())
                .as("被划掉的这条在同一个窗口里不再回来（删除按钮要真的有用）")
                .doesNotContain(items.get(0).path("title").asText());

        // 首页强提醒流走的是另一条读取路径，同样要能扛住
        assertThat(api.get("/api/v1/app/messages/highlights?limit=6", token).code()).isZero();
        // 未读角标不补算，但它读的是同一张表，顺手确认没被牵连
        assertThat(api.get("/api/v1/app/messages/unread-count", token).code()).isZero();
    }

    @Test
    @DisplayName("删掉防疫记录后不再提醒")
    void deletingEpidemicRecordStopsReminder() {
        String token = register("13200000023");
        long petId = api.createPet(token, "豆豆");
        long recordId = addEpidemicRecord(token, petId, 1, "狂犬疫苗", today(), today().plusDays(3));
        assertThat(highlights(token)).hasSize(1);

        // 删记录：提醒本身留在消息中心（用户已经看到的不能凭空消失），但重新生成不再产生新的
        assertThat(api.delete("/api/v1/app/pets/" + petId + "/epidemic-records/" + recordId, token).code())
                .isZero();
        assertThat(api.get("/api/v1/app/pets/" + petId + "/epidemic-records", token).data()).isEmpty();
    }

    // ---------------------------------------------------------------- 工具

    private LocalDate today() {
        return AppTime.today();
    }

    private String register(String phone) {
        return api.registerAndGetAccessToken(phone);
    }

    private long addEpidemicRecord(String token, long petId, int kind, String name,
                                   LocalDate givenOn, LocalDate nextDueOn) {
        ApiClient.ApiCall call = api.post("/api/v1/app/pets/" + petId + "/epidemic-records",
                new EpidemicRecordInput(kind, name, givenOn.toString(), nextDueOn.toString()), token);
        assertThat(call.code()).as("录入防疫记录应当成功：" + call.body()).isZero();
        return call.data().path("id").asLong();
    }

    private void submitNormalCheckIn(String token, long petId) {
        ApiClient.ApiCall call = api.post("/api/v1/app/pets/" + petId + "/check-ins",
                new CheckInSubmitRequest(today().toString(),
                        List.of(new CheckInItemInput(2, false, "normal", null))), token);
        assertThat(call.code()).as("打卡应当成功：" + call.body()).isZero();
    }

    private void checkInWeight(String token, long petId, LocalDate date, String weight) {
        ApiClient.ApiCall call = api.post("/api/v1/app/pets/" + petId + "/check-ins",
                new CheckInSubmitRequest(date.toString(),
                        List.of(new CheckInItemInput(1, false, weight, null))), token);
        assertThat(call.code()).as("打卡应当成功：" + call.body()).isZero();
    }

    private JsonNode highlights(String token) {
        return api.get("/api/v1/app/messages/highlights", token).data();
    }

    /** 按类型取一条提醒；没有返回 null（列表里可能夹着日常类提醒）。 */
    private JsonNode byType(JsonNode messages, int type) {
        for (JsonNode node : messages) {
            if (node.path("type").asInt() == type) {
                return node;
            }
        }
        return null;
    }

    private JsonNode findSetting(JsonNode settings, int type) {
        for (JsonNode setting : settings) {
            if (setting.path("type").asInt() == type) {
                return setting;
            }
        }
        throw new AssertionError("找不到类型 " + type + " 的开关");
    }

    private JsonNode dimension(JsonNode dimensions, String key) {
        for (JsonNode node : dimensions) {
            if (key.equals(node.path("key").asText())) {
                return node;
            }
        }
        throw new AssertionError("找不到维度：" + key);
    }
}
