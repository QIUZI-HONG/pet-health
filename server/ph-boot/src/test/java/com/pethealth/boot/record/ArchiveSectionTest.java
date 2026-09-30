package com.pethealth.boot.record;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethealth.api.app.ArchiveRecordRequest;
import com.pethealth.api.app.CheckInItemRequest;
import com.pethealth.api.app.CheckInSubmitRequest;
import com.pethealth.api.app.EpidemicRecordRequest;
import com.pethealth.boot.support.ApiClient;
import com.pethealth.boot.support.IntegrationTestBase;
import com.pethealth.common.time.AppTime;
import com.pethealth.record.domain.ArchiveRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 档案分项、分项记录与时间轴（切片 #102，字段清单与多源规则见 ADR-0030）。
 *
 * <p>用例逐条对应 ADR 里定下的口径：8 个分项的固定形状、只收列表语义的写入口、7 天窗口、
 * 多源权威标注（**冲突两条都留、AI 永不权威**）、时间轴只收四类事件，以及越权与删除幂等。
 */
class ArchiveSectionTest extends IntegrationTestBase {

    private static final String BASE = "/api/v1/app/pets/";

    private ApiClient api;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUpClient() {
        api = new ApiClient(rest, objectMapper);
    }

    // ---------------------------------------------------------------- 分项概览

    @Test
    @DisplayName("分项概览固定返回 8 个入口，顺序与交付文档 4.16.4 一致")
    void sectionsAreFixedAndOrdered() {
        String token = register("13600000101");
        long petId = api.createPet(token, "豆豆");

        JsonNode sections = api.get(BASE + petId + "/archive-sections", token).data();

        assertThat(sections).hasSize(8);
        assertThat(sections.get(0).path("code").asText()).isEqualTo("metrics");
        assertThat(sections.get(1).path("code").asText()).isEqualTo("documents");
        assertThat(sections.get(7).path("code").asText()).isEqualTo("media");
        // 照片视频的内容在文件模块：条数为 null、不可用分项接口录入（ADR-0023 第二条）
        assertThat(sections.get(7).path("content_source").asText()).isEqualTo("files");
        assertThat(sections.get(7).path("record_count").isNull()).isTrue();
        assertThat(sections.get(7).path("recordable").asBoolean()).isFalse();
        // 字段名与契约一致（前端类型由契约生成，名字写错 TS 那边不会红）
        assertThat(sections.get(0).fieldNames()).toIterable().containsExactlyInAnyOrder(
                "code", "name", "description", "content_source", "recordable", "enabled",
                "disabled_reason", "record_count", "latest_date");
    }

    @Test
    @DisplayName("老年专项在照护模式未开启时是灰态入口（enabled=false + 原因）")
    void elderlySectionFollowsCareMode() {
        String token = register("13600000102");
        long youngPet = api.createPetDetailed(token, "小狗", LocalDate.now().minusYears(2), false);
        long oldPet = api.createPetDetailed(token, "老狗", LocalDate.now().minusYears(9), false);

        JsonNode young = section(api.get(BASE + youngPet + "/archive-sections", token).data(), "elderly");
        JsonNode old = section(api.get(BASE + oldPet + "/archive-sections", token).data(), "elderly");

        assertThat(young.path("enabled").asBoolean()).isFalse();
        assertThat(young.path("disabled_reason").asText()).contains("7 岁");
        assertThat(old.path("enabled").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("条数与最近日期按分项合并（核心指标 = 体重 + 排泄 + 其他指标）")
    void sectionCountsMergeCategories() {
        String token = register("13600000103");
        long petId = api.createPet(token, "豆豆");

        // 打卡记体重（category=1），分项接口记体温（category=10）——两笔都算「核心指标」
        api.post(BASE + petId + "/check-ins",
                new CheckInSubmitRequest(AppTime.today().toString(),
                        List.of(new CheckInItemRequest(1, false, "12.50", null))), token);
        create(token, petId, new ArchiveRecordRequest("metrics", AppTime.today().toString(),
                "体温", "38.5", "℃", null, null, null));

        JsonNode metrics = section(api.get(BASE + petId + "/archive-sections", token).data(), "metrics");
        assertThat(metrics.path("record_count").asInt()).isEqualTo(2);
        assertThat(metrics.path("latest_date").asText()).isEqualTo(AppTime.today().toString());
    }

    // ---------------------------------------------------------------- 记录读写

    @Test
    @DisplayName("录入、修改、删除一条分项记录（字段名与契约一致）")
    void createUpdateDelete() {
        String token = register("13600000104");
        long petId = api.createPet(token, "豆豆");

        ApiClient.ApiCall created = create(token, petId, new ArchiveRecordRequest("documents",
                AppTime.today().toString(), "免疫证", "SH-2026-001", null, null, null, null));

        assertThat(created.data().fieldNames()).toIterable().containsExactlyInAnyOrder("id", "section",
                "category", "record_date", "title", "value", "unit", "due_on", "note", "abnormal",
                "source", "source_label", "authoritative", "backfilled", "created_at");
        assertThat(created.data().path("category").asInt()).isEqualTo(11);
        assertThat(created.data().path("section").asText()).isEqualTo("documents");
        assertThat(created.data().path("source_label").asText()).isEqualTo("用户录入");
        long recordId = created.data().path("id").asLong();

        // 改：换到期日（证件续期就是改这一条）
        ApiClient.ApiCall updated = api.put(BASE + petId + "/archive-records/" + recordId,
                new ArchiveRecordRequest("documents", AppTime.today().toString(), "免疫证", "SH-2026-001",
                        null, AppTime.today().plusYears(1).toString(), "续期", null), token);
        assertThat(updated.code()).isZero();
        assertThat(updated.data().path("due_on").asText()).isEqualTo(AppTime.today().plusYears(1).toString());

        // 删（软删除，幂等：再删一次也成功）
        assertThat(api.delete(BASE + petId + "/archive-records/" + recordId, token).code()).isZero();
        assertThat(api.delete(BASE + petId + "/archive-records/" + recordId, token).code()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT is_deleted FROM archive_record WHERE id = ?", Integer.class, recordId)).isEqualTo(1);
    }

    @Test
    @DisplayName("体重与排泄不能在分项接口写入（打卡语义各有自己的入口）")
    void onlyListSectionsAreWritable() {
        String token = register("13600000105");
        long petId = api.createPet(token, "豆豆");

        for (String sectionCode : List.of("behavior", "mood", "hygiene", "diet", "media")) {
            ApiClient.ApiCall call = create(token, petId, new ArchiveRecordRequest(sectionCode,
                    AppTime.today().toString(), "试着写", null, null, null, null, null));
            assertThat(call.status()).as("分项 %s 不应可写", sectionCode).isEqualTo(400);
            assertThat(call.code()).as("分项 %s 应返回 40001", sectionCode).isEqualTo(40001);
        }
        // 一条都没落库
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM archive_record WHERE pet_id = ?", Integer.class, petId)).isZero();
    }

    @Test
    @DisplayName("参数边界：将来 / 8 天前 / 到期日早于记录日 / 标题空 / 分项不存在 → 40001")
    void paramBoundaries() {
        String token = register("13600000106");
        long petId = api.createPet(token, "豆豆");

        assertThat(create(token, petId, new ArchiveRecordRequest("elderly",
                AppTime.today().plusDays(1).toString(), "复查", null, null, null, null, null)).code())
                .isEqualTo(40001);
        assertThat(create(token, petId, new ArchiveRecordRequest("elderly",
                AppTime.today().minusDays(8).toString(), "复查", null, null, null, null, null)).code())
                .isEqualTo(40001);
        assertThat(create(token, petId, new ArchiveRecordRequest("documents",
                AppTime.today().toString(), "免疫证", null, null,
                AppTime.today().minusDays(1).toString(), null, null)).code()).isEqualTo(40001);
        assertThat(create(token, petId, new ArchiveRecordRequest("documents",
                AppTime.today().toString(), "   ", null, null, null, null, null)).code()).isEqualTo(40001);
        assertThat(create(token, petId, new ArchiveRecordRequest("unknown",
                AppTime.today().toString(), "x", null, null, null, null, null)).code()).isEqualTo(40001);
        // 照片视频的内容在文件接口，从档案记录里读它是参数错误（返回空列表会被读成「没有照片」）
        assertThat(api.get(BASE + petId + "/archive-records?section=media", token).code()).isEqualTo(40001);
        // 日期格式对但日子不存在（测试报告 D11 的同一类）
        assertThat(create(token, petId, new ArchiveRecordRequest("elderly",
                "2026-02-31", "复查", null, null, null, null, null)).code()).isEqualTo(40001);
    }

    @Test
    @DisplayName("别人的宠物：分项、记录读写、时间轴一律 40400")
    void otherUsersPetIsInvisible() {
        String ownerToken = register("13600000107");
        long petId = api.createPet(ownerToken, "豆豆");
        long recordId = create(ownerToken, petId, new ArchiveRecordRequest("elderly",
                AppTime.today().toString(), "复查", null, null, null, "一切正常", null))
                .data().path("id").asLong();
        String intruder = register("13600000108");

        assertThat(api.get(BASE + petId + "/archive-sections", intruder).code()).isEqualTo(40400);
        assertThat(api.get(BASE + petId + "/archive-records", intruder).code()).isEqualTo(40400);
        assertThat(create(intruder, petId, new ArchiveRecordRequest("elderly",
                AppTime.today().toString(), "复查", null, null, null, null, null)).code()).isEqualTo(40400);
        assertThat(api.put(BASE + petId + "/archive-records/" + recordId,
                new ArchiveRecordRequest("elderly", AppTime.today().toString(), "改",
                        null, null, null, null, null), intruder).code()).isEqualTo(40400);
        assertThat(api.delete(BASE + petId + "/archive-records/" + recordId, intruder).code()).isEqualTo(40400);
        assertThat(api.get(BASE + petId + "/timeline", intruder).code()).isEqualTo(40400);
    }

    @Test
    @DisplayName("打卡与防疫的行不能从分项接口删除（各有自己的接口）")
    void checkInRowsCannotBeDeletedHere() {
        String token = register("13600000109");
        long petId = api.createPet(token, "豆豆");
        api.post(BASE + petId + "/check-ins", new CheckInSubmitRequest(AppTime.today().toString(),
                List.of(new CheckInItemRequest(2, false, "normal", null))), token);
        long recordId = jdbc.queryForObject(
                "SELECT id FROM archive_record WHERE pet_id = ? AND category = 2", Long.class, petId);

        ApiClient.ApiCall call = api.delete(BASE + petId + "/archive-records/" + recordId, token);

        assertThat(call.code()).isEqualTo(40001);
        assertThat(jdbc.queryForObject(
                "SELECT is_deleted FROM archive_record WHERE id = ?", Integer.class, recordId)).isZero();
    }

    // ---------------------------------------------------------------- 多源写入

    @Test
    @DisplayName("多源：证件以服务者报工为准、自述类以用户为准；冲突两条都留")
    void authorityFollowsSectionRule() {
        String token = register("13600000110");
        long petId = api.createPet(token, "豆豆");

        // 用户先录了一张证件，随后服务者报工写了同分项同日的记录（模拟 #107 的写入路径）
        create(token, petId, new ArchiveRecordRequest("documents", AppTime.today().toString(),
                "免疫证", "用户填的号", null, null, null, null));
        insertDirect(petId, ArchiveRecord.CATEGORY_DOCUMENT, ArchiveRecord.SOURCE_PROVIDER,
                "免疫证", "服务者登记的号");

        JsonNode records = api.get(BASE + petId + "/archive-records?section=documents", token).data();
        assertThat(records.path("total").asLong()).isEqualTo(2);          // 冲突两条都留，没有覆盖
        assertThat(authoritative(records.path("list")).path("source").asInt()).isEqualTo(3);
        assertThat(authoritative(records.path("list")).path("value").asText()).isEqualTo("服务者登记的号");

        // 自述类（老年专项）反过来：用户是权威，AI 的记录不是
        insertDirect(petId, ArchiveRecord.CATEGORY_ELDERLY, ArchiveRecord.SOURCE_AI, "AI 建议", "多喝水");
        create(token, petId, new ArchiveRecordRequest("elderly", AppTime.today().toString(),
                "复查", "一切正常", null, null, null, null));
        JsonNode elderly = api.get(BASE + petId + "/archive-records?section=elderly", token).data();
        assertThat(authoritative(elderly.path("list")).path("source").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("只有 AI 记录时也不把它当权威（AI 只产生建议）")
    void aiIsNeverAuthoritative() {
        String token = register("13600000111");
        long petId = api.createPet(token, "豆豆");

        insertDirect(petId, ArchiveRecord.CATEGORY_DOCUMENT, ArchiveRecord.SOURCE_AI, "AI 建议", "x");

        JsonNode records = api.get(BASE + petId + "/archive-records?section=documents", token).data();
        assertThat(records.path("list").get(0).path("authoritative").asBoolean()).isFalse();
    }

    // ---------------------------------------------------------------- 时间轴

    @Test
    @DisplayName("时间轴只收四类事件：就医、疫苗/驱虫、异常打卡、服务者报工（正常打卡不进）")
    void timelineCollectsFourTypes() {
        String token = register("13600000112");
        long petId = api.createPet(token, "豆豆");

        // 正常打卡（不进时间轴）
        api.post(BASE + petId + "/check-ins", new CheckInSubmitRequest(AppTime.today().toString(),
                List.of(new CheckInItemRequest(2, false, "normal", null))), token);
        // 异常打卡（进）
        api.post(BASE + petId + "/check-ins", new CheckInSubmitRequest(AppTime.today().minusDays(1).toString(),
                List.of(new CheckInItemRequest(3, true, "loose", "有点软"))), token);
        // 疫苗（进）
        api.post(BASE + petId + "/epidemic-records", new EpidemicRecordRequest(1, "狂犬疫苗",
                AppTime.today().minusDays(2).toString(), AppTime.today().plusDays(10).toString()), token);
        // 就医（进）
        create(token, petId, new ArchiveRecordRequest("medical", AppTime.today().toString(),
                "呕吐就诊", null, null, null, "在康宠医院看了一次", null));
        // 服务者报工（进）
        insertDirect(petId, ArchiveRecord.CATEGORY_BEHAVIOR, ArchiveRecord.SOURCE_PROVIDER, "行为观察", "有点紧张");

        JsonNode timeline = api.get(BASE + petId + "/timeline", token).data();

        assertThat(timeline.path("total").asLong()).isEqualTo(4);
        List<String> types = new ArrayList<>();
        timeline.path("list").forEach(node -> types.add(node.path("type").asText()));
        assertThat(types).containsExactlyInAnyOrder("abnormal", "vaccine", "medical", "provider");
        // 倒序（docs/conventions.md 的默认排序）
        assertThat(timeline.path("list").get(0).path("date").asText())
                .isGreaterThanOrEqualTo(timeline.path("list").get(3).path("date").asText());

        JsonNode vaccineOnly = api.get(BASE + petId + "/timeline?type=vaccine", token).data();
        assertThat(vaccineOnly.path("total").asLong()).isEqualTo(1);
        assertThat(vaccineOnly.path("list").get(0).path("title").asText()).isEqualTo("狂犬疫苗");
        // 契约枚举以外的类型 → 40001（不静默当成「全部」）
        assertThat(api.get(BASE + petId + "/timeline?type=nope", token).code()).isEqualTo(40001);
    }

    @Test
    @DisplayName("导出包里含分项记录（content 槽承载结构化载荷）")
    void exportContainsSectionPayload() {
        String token = register("13600000113");
        long petId = api.createPet(token, "豆豆");
        create(token, petId, new ArchiveRecordRequest("documents", AppTime.today().toString(),
                "免疫证", "SH-1", null, null, null, null));

        JsonNode pet = api.get("/api/v1/app/users/me/export", token).data().path("pets").get(0);

        boolean found = false;
        for (JsonNode record : pet.path("records")) {
            if (record.path("category").asInt() == ArchiveRecord.CATEGORY_DOCUMENT) {
                found = true;
                assertThat(record.path("content").asText()).contains("免疫证");
            }
        }
        assertThat(found).as("导出里应有证件那一行").isTrue();
    }

    // ---------------------------------------------------------------- 工具

    private ApiClient.ApiCall create(String token, long petId, ArchiveRecordRequest request) {
        return api.post(BASE + petId + "/archive-records", request, token);
    }

    /** 直接往库里写一行（模拟 AI 建议与服务者报工——它们的写入路径 #107 落地后由那边写）。 */
    private void insertDirect(long petId, int category, int source, String title, String value) {
        jdbc.update("""
                INSERT INTO archive_record (pet_id, user_id, record_date, category, structured_payload,
                                            source, abnormal, backfilled, created_at, updated_at,
                                            created_by, updated_by, trace_id, is_deleted)
                SELECT id, user_id, ?, ?, JSON_OBJECT('title', ?, 'value', ?), ?, 0, 0, NOW(), NOW(), 0, 0, '', 0
                  FROM pet WHERE id = ?
                """, AppTime.today(), category, title, value, source, petId);
    }

    private JsonNode section(JsonNode sections, String code) {
        for (JsonNode node : sections) {
            if (code.equals(node.path("code").asText())) {
                return node;
            }
        }
        throw new AssertionError("找不到分项：" + code + "，实际内容：" + sections);
    }

    private JsonNode authoritative(JsonNode list) {
        for (JsonNode node : list) {
            if (node.path("authoritative").asBoolean()) {
                return node;
            }
        }
        throw new AssertionError("这一页没有权威记录：" + list);
    }

    private String register(String phone) {
        return api.registerAndGetAccessToken(phone);
    }
}
