package com.pethealth.boot.record;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethealth.boot.support.ApiClient;
import com.pethealth.boot.support.IntegrationTestBase;
import com.pethealth.common.time.AppTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.DayOfWeek;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 健康报告（切片 #115，决策见 ADR-0031）。
 *
 * <p>用例钉住拍板过的口径：**只做两种报告**、周期取**上一个完整周期**、生成**幂等**
 * （重复读只留一行）、**没有数据也要出一份**（不报错、不说 0 分）、保留 12 期、
 * 完整版段落只标注不拦截、越权 40400 与类型边界 40001。
 *
 * <p>数据用 jdbc 直接写进「上一个完整周/月」——打卡只能补录 7 天，而上一周的周一可能已经
 * 在窗口之外（今天是周日时上一周是 7–13 天前）。这不影响被测的行为：报告读的就是这两张表。
 */
class HealthReportTest extends IntegrationTestBase {

    private static final String BASE = "/api/v1/app/pets/";

    private ApiClient api;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUpClient() {
        api = new ApiClient(rest, objectMapper);
    }

    @Test
    @DisplayName("周报：周期是上一个完整自然周（周一至周日），数字与记录一致")
    void weeklyReportCoversLastWholeWeek() {
        String token = register("13800000101");
        long petId = api.createPet(token, "豆豆");
        LocalDate periodEnd = AppTime.today().with(DayOfWeek.MONDAY).minusDays(1);
        LocalDate periodStart = periodEnd.minusDays(6);
        insertRecord(petId, periodStart, 1, false);
        insertRecord(petId, periodStart.plusDays(1), 3, true);
        insertScore(petId, periodStart, 60);
        insertScore(petId, periodStart.plusDays(1), 70);

        JsonNode report = only(api.get(BASE + petId + "/health-reports?type=1", token).data());

        assertThat(report.path("type").asInt()).isEqualTo(1);
        assertThat(report.path("type_name").asText()).isEqualTo("健康周报");
        assertThat(report.path("period_start").asText()).isEqualTo(periodStart.toString());
        assertThat(report.path("period_end").asText()).isEqualTo(periodEnd.toString());
        assertThat(report.path("total_score").asInt()).isEqualTo(65);
        assertThat(report.path("grade").asText()).isEqualTo("需关注");
        // 四段结构固定（本轮拍板）
        JsonNode sections = report.path("payload").path("sections");
        assertThat(sections).hasSize(4);
        assertThat(sections.get(0).path("code").asText()).isEqualTo("score_and_trend");
        assertThat(sections.get(1).path("code").asText()).isEqualTo("abnormal_and_highlights");
        assertThat(sections.get(2).path("code").asText()).isEqualTo("checkin_completion");
        assertThat(sections.get(3).path("code").asText()).isEqualTo("suggestions");
        // 权益只标注不拦截：需要权益的段落内容照常返回
        assertThat(sections.get(3).path("requires_privilege").asBoolean()).isTrue();
        assertThat(sections.get(3).path("lines").size()).isGreaterThan(0);
        assertThat(report.path("privilege_code").asText()).isEqualTo("report.full");
        // 汇总数字与库里的记录对得上
        assertThat(report.path("payload").path("stats").path("record_days").asInt()).isEqualTo(2);
        assertThat(report.path("payload").path("stats").path("abnormal_count").asInt()).isEqualTo(1);
        // 口径说明必须在（免责 + 「不是医学建议」）
        assertThat(report.path("payload").path("notice").asText()).contains("不能替代兽医诊断");
    }

    @Test
    @DisplayName("月报：周期是上一个完整自然月")
    void monthlyReportCoversLastMonth() {
        String token = register("13800000102");
        long petId = api.createPet(token, "豆豆");
        LocalDate periodEnd = AppTime.today().withDayOfMonth(1).minusDays(1);
        LocalDate periodStart = periodEnd.withDayOfMonth(1);

        JsonNode report = only(api.get(BASE + petId + "/health-reports?type=2", token).data());

        assertThat(report.path("type_name").asText()).isEqualTo("健康月报");
        assertThat(report.path("period_start").asText()).isEqualTo(periodStart.toString());
        assertThat(report.path("period_end").asText()).isEqualTo(periodEnd.toString());
    }

    @Test
    @DisplayName("没有数据也出一份报告：不报错、total_score 为 null、文案说清「还没有记录」")
    void emptyPeriodStillProducesReport() {
        String token = register("13800000103");
        long petId = api.createPet(token, "豆豆");

        JsonNode report = only(api.get(BASE + petId + "/health-reports?type=1", token).data());

        assertThat(report.path("total_score").isNull()).isTrue();
        assertThat(report.path("grade").isNull()).isTrue();
        assertThat(report.path("payload").path("stats").path("record_days").asInt()).isZero();
        assertThat(report.path("payload").path("sections").get(0).path("lines").toString())
                .contains("还没有评分");
    }

    @Test
    @DisplayName("幂等：同一天读两次只留一行（唯一键，不是先查再插）")
    void generatingTwiceKeepsOneRow() {
        String token = register("13800000104");
        long petId = api.createPet(token, "豆豆");

        api.get(BASE + petId + "/health-reports?type=1", token);
        api.get(BASE + petId + "/health-reports?type=1", token);
        api.get(BASE + petId + "/health-reports", token);   // 不带 type：两类都补齐

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM health_report WHERE pet_id = ? AND type = 1", Integer.class, petId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM health_report WHERE pet_id = ?", Integer.class, petId)).isEqualTo(2);
    }

    @Test
    @DisplayName("历史周期不回改：已有报告的那一期不会被重算")
    void historicalPeriodIsNotRewritten() {
        String token = register("13800000105");
        long petId = api.createPet(token, "豆豆");
        LocalDate periodEnd = AppTime.today().with(DayOfWeek.MONDAY).minusDays(1);
        LocalDate periodStart = periodEnd.minusDays(6);
        insertRecord(petId, periodStart, 1, false);

        String firstPayload = only(api.get(BASE + petId + "/health-reports?type=1", token).data())
                .path("payload").toString();

        // 事后补录同一周期的记录（快照理应不变——报告记的是「当时」）
        insertRecord(petId, periodStart.plusDays(2), 3, true);
        String secondPayload = only(api.get(BASE + petId + "/health-reports?type=1", token).data())
                .path("payload").toString();

        assertThat(secondPayload).isEqualTo(firstPayload);
    }

    @Test
    @DisplayName("保留最近 12 期：第 13 期生成时最老的一期被软删")
    void keepsOnlyTwelvePeriods() {
        String token = register("13800000106");
        long petId = api.createPet(token, "豆豆");
        LocalDate periodEnd = AppTime.today().with(DayOfWeek.MONDAY).minusDays(1);
        // 先塞 12 期历史（不含「上一个完整周」那一期）
        for (int i = 0; i < 12; i++) {
            LocalDate end = periodEnd.minusWeeks(i + 1L);
            insertReport(petId, 1, end.minusDays(6), 50 + i);
        }

        // 惰性补齐会写进第 13 期（上一个完整周），并裁掉最老的一期
        api.get(BASE + petId + "/health-reports?type=1", token);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM health_report WHERE pet_id = ? AND type = 1 AND is_deleted = 0",
                Integer.class, petId)).isEqualTo(12);

        JsonNode page = api.get(BASE + petId + "/health-reports?type=1", token).data();
        assertThat(page.path("total").asLong()).isEqualTo(12);
        assertThat(page.path("list").get(0).path("period_start").asText()).isEqualTo(periodEnd.minusDays(6).toString());
    }

    @Test
    @DisplayName("越权 40400；类型只接受 1/2（type=3 是 40001）")
    void accessAndTypeBoundaries() {
        String ownerToken = register("13800000107");
        long petId = api.createPet(ownerToken, "豆豆");
        String intruder = register("13800000108");

        assertThat(api.get(BASE + petId + "/health-reports", intruder).code()).isEqualTo(40400);
        assertThat(api.get(BASE + petId + "/health-reports?type=3", ownerToken).code()).isEqualTo(40001);
        assertThat(api.get(BASE + petId + "/health-reports?page=0", ownerToken).code()).isEqualTo(40001);
        assertThat(api.get(BASE + petId + "/health-reports?page_size=101", ownerToken).code()).isEqualTo(40001);
    }

    @Test
    @DisplayName("导出包里含报告（没有报告时是空数组，不是 null）")
    void exportContainsReports() {
        String token = register("13800000109");
        long petId = api.createPet(token, "豆豆");

        // 没有报告时：空数组
        assertThat(api.get("/api/v1/app/users/me/export", token).data()
                .path("pets").get(0).path("reports")).isEmpty();

        api.get(BASE + petId + "/health-reports?type=1", token);

        JsonNode reports = api.get("/api/v1/app/users/me/export", token).data()
                .path("pets").get(0).path("reports");
        assertThat(reports).hasSize(1);
        assertThat(reports.get(0).path("type").asInt()).isEqualTo(1);
        assertThat(reports.get(0).path("payload").path("sections")).hasSize(4);
    }

    // ---------------------------------------------------------------- 工具

    private JsonNode only(JsonNode page) {
        assertThat(page.path("total").asLong()).as("应当只有一期报告：" + page).isEqualTo(1);
        return page.path("list").get(0);
    }

    private void insertRecord(long petId, LocalDate date, int category, boolean abnormal) {
        jdbc.update("""
                INSERT INTO archive_record (pet_id, user_id, record_date, category, content,
                                            source, abnormal, backfilled, created_at, updated_at,
                                            created_by, updated_by, trace_id, is_deleted)
                SELECT id, user_id, ?, ?, JSON_OBJECT('status', 'normal'), 1, ?, 0, NOW(), NOW(), 0, 0, '', 0
                  FROM pet WHERE id = ?
                """, date, category, abnormal ? 1 : 0, petId);
    }

    private void insertScore(long petId, LocalDate date, int total) {
        jdbc.update("""
                INSERT INTO health_score (pet_id, total_score, physiology, behavior, hygiene,
                                          included_dimensions, calc_date, created_at, updated_at,
                                          created_by, updated_by, trace_id, is_deleted)
                VALUES (?, ?, ?, ?, ?, 3, ?, NOW(), NOW(), 0, 0, '', 0)
                """, petId, total, total, total, total, date);
    }

    private void insertReport(long petId, int type, LocalDate start, int total) {
        jdbc.update("""
                INSERT INTO health_report (pet_id, user_id, type, period_start, period_end, grade,
                                           total_score, tier, payload, generated_at, created_at, updated_at,
                                           created_by, updated_by, trace_id, is_deleted)
                SELECT id, user_id, ?, ?, ?, '尚可', ?, 2,
                       JSON_OBJECT('headline', 'x', 'sections', JSON_ARRAY(),
                                   'stats', JSON_OBJECT(), 'notice', 'x'),
                       NOW(), NOW(), NOW(), 0, 0, '', 0
                  FROM pet WHERE id = ?
                """, type, start, start.plusDays(6), total, petId);
    }

    private String register(String phone) {
        return api.registerAndGetAccessToken(phone);
    }
}
