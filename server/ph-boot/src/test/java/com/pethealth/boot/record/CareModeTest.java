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

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 专项照护模式（切片 #116，判定与四项变化见 ADR-0032）。
 *
 * <p>这些用例钉住的是**状态不变量**：派生（生日实时算 + 慢病）、可手动关闭、关闭后老年维不再计入
 * 并重算当日评分行、阈值来自数据（不是代码里的 7）、越权 40400。
 * 顺带钉住提醒的档位差异（照护档提前量收紧，普通档阈值不变）——那是本轮授权改的 ph-reminder 接线。
 */
class CareModeTest extends IntegrationTestBase {

    private static final String BASE = "/api/v1/app/pets/";

    private ApiClient api;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUpClient() {
        api = new ApiClient(rest, objectMapper);
    }

    @Test
    @DisplayName("9 岁生日实时算 → 开启；2 岁无慢病 → 未开启（不落状态位）")
    void careModeIsDerivedFromBirthday() {
        String token = register("13700000101");
        long oldPet = api.createPetDetailed(token, "老狗", LocalDate.now().minusYears(9), false);
        long youngPet = api.createPetDetailed(token, "小狗", LocalDate.now().minusYears(2), false);

        JsonNode old = api.get(BASE + oldPet + "/care-mode", token).data();
        JsonNode young = api.get(BASE + youngPet + "/care-mode", token).data();

        assertThat(old.path("active").asBoolean()).isTrue();
        assertThat(old.path("reasons").get(0).path("code").asText()).isEqualTo("elderly");
        assertThat(old.path("age_years").asInt()).isEqualTo(9);
        assertThat(old.path("age_text").asText()).contains("9 岁");
        assertThat(old.path("age_threshold_years").asInt()).isEqualTo(7);
        assertThat(old.path("elderly_since").asText()).isEqualTo(LocalDate.now().minusYears(2).toString());
        // 四项变化的说明由后端给（医疗口径不散落到前端）
        assertThat(old.path("effects")).hasSizeGreaterThanOrEqualTo(4);

        assertThat(young.path("active").asBoolean()).isFalse();
        assertThat(young.path("reasons")).isEmpty();
        // 未开启也要给说明：用户要知道开了会发生什么
        assertThat(young.path("effects")).hasSizeGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("慢病自述即开启；没有生日也不报错")
    void careModeHandlesChronicAndMissingBirthday() {
        String token = register("13700000102");
        long chronicPet = api.createPetDetailed(token, "病猫", LocalDate.now().minusYears(3), true);
        long noBirthday = api.createPet(token, "没生日");

        JsonNode chronic = api.get(BASE + chronicPet + "/care-mode", token).data();
        assertThat(chronic.path("active").asBoolean()).isTrue();
        assertThat(chronic.path("reasons").get(0).path("code").asText()).isEqualTo("chronic");
        assertThat(chronic.path("chronic_desc").asText()).isEqualTo("慢病照护");
        // 慢病与年龄互不影响：这只宠物既有生日也有慢病，两件事都如实给出
        assertThat(chronic.path("age_text").asText()).contains("3 岁");
        assertThat(chronic.path("age_years").asInt()).isEqualTo(3);

        JsonNode none = api.get(BASE + noBirthday + "/care-mode", token).data();
        assertThat(none.path("active").asBoolean()).isFalse();
        assertThat(none.path("age_text").isNull()).isTrue();
        assertThat(none.path("elderly_since").isNull()).isTrue();
    }

    @Test
    @DisplayName("手动关闭：active 变 false、原因仍如实给出；重新打开后回到派生结果")
    void manualCloseAndReopen() {
        String token = register("13700000103");
        long petId = api.createPetDetailed(token, "老狗", LocalDate.now().minusYears(9), false);

        JsonNode closed = api.put(BASE + petId + "/care-mode", java.util.Map.of("enabled", false), token).data();
        assertThat(closed.path("active").asBoolean()).isFalse();
        assertThat(closed.path("disabled_by_user").asBoolean()).isTrue();
        assertThat(closed.path("reasons")).hasSize(1);                       // 派生原因不因为关掉而消失
        assertThat(closed.path("effects").toString()).contains("已手动关闭");

        JsonNode reopened = api.put(BASE + petId + "/care-mode", java.util.Map.of("enabled", true), token).data();
        assertThat(reopened.path("active").asBoolean()).isTrue();
        assertThat(reopened.path("disabled_by_user").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("关闭后老年维不计入总分，且当日评分行被重算（趋势图不留旧口径的点）")
    void closingRecalculatesTodayScore() {
        String token = register("13700000104");
        long petId = api.createPetDetailed(token, "老狗", LocalDate.now().minusYears(9), false);
        api.post(BASE + petId + "/check-ins", new com.pethealth.api.app.CheckInSubmitRequest(
                AppTime.today().toString(),
                java.util.List.of(new com.pethealth.api.app.CheckInItemRequest(1, false, "10.00", null))), token);

        // 开启态：老年维计入（落库那一行有分）
        assertThat(dimension(api.get(BASE + petId + "/health-score", token).data(), "elderly")
                .path("included").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT elderly FROM health_score WHERE pet_id = ? AND calc_date = ?",
                Integer.class, petId, AppTime.today())).isNotNull();

        api.put(BASE + petId + "/care-mode", java.util.Map.of("enabled", false), token);

        // 关闭态：老年维不计入，且**当日行已经重算**（库里那一列被写回 NULL）
        assertThat(dimension(api.get(BASE + petId + "/health-score", token).data(), "elderly")
                .path("included").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT elderly FROM health_score WHERE pet_id = ? AND calc_date = ?",
                Integer.class, petId, AppTime.today())).isNull();
    }

    @Test
    @DisplayName("年龄阈值来自 care_mode_rule（改数据即生效，不是代码里的 7）")
    void thresholdComesFromData() {
        String token = register("13700000105");
        long petId = api.createPetDetailed(token, "老狗", LocalDate.now().minusYears(9), false);
        Integer original = jdbc.queryForObject(
                "SELECT min_age_years FROM care_mode_rule WHERE species = 1", Integer.class);
        try {
            jdbc.update("UPDATE care_mode_rule SET min_age_years = 12 WHERE species = 1");

            JsonNode after = api.get(BASE + petId + "/care-mode", token).data();
            assertThat(after.path("active").asBoolean()).isFalse();
            assertThat(after.path("age_threshold_years").asInt()).isEqualTo(12);
            // 分项入口跟着变灰（老年专项）
            JsonNode elderly = section(api.get(BASE + petId + "/archive-sections", token).data(), "elderly");
            assertThat(elderly.path("enabled").asBoolean()).isFalse();
        } finally {
            jdbc.update("UPDATE care_mode_rule SET min_age_years = ? WHERE species = 1", original);
        }
    }

    @Test
    @DisplayName("别人的宠物：读与写都 40400")
    void otherUsersPetIsInvisible() {
        String ownerToken = register("13700000106");
        long petId = api.createPetDetailed(ownerToken, "老狗", LocalDate.now().minusYears(9), false);
        String intruder = register("13700000107");

        assertThat(api.get(BASE + petId + "/care-mode", intruder).code()).isEqualTo(40400);
        assertThat(api.put(BASE + petId + "/care-mode", java.util.Map.of("enabled", false), intruder).code())
                .isEqualTo(40400);
    }

    @Test
    @DisplayName("照护档收紧提醒阈值：同样的防疫到期日，照护宠提醒、非照护宠不提醒（普通档阈值不变）")
    void careModeTightensReminderThresholds() {
        String careToken = register("13700000108");
        long carePet = api.createPetDetailed(careToken, "老狗", LocalDate.now().minusYears(9), false);
        String normalToken = register("13700000109");
        long normalPet = api.createPetDetailed(normalToken, "小狗", LocalDate.now().minusYears(2), false);

        // 到期日落在「照护档 30 天」内、但超出「普通档 7 天」
        String due = AppTime.today().plusDays(15).toString();
        for (String token : java.util.List.of(careToken, normalToken)) {
            long petId = token.equals(careToken) ? carePet : normalPet;
            api.post(BASE + petId + "/epidemic-records",
                    new com.pethealth.api.app.EpidemicRecordRequest(1, "狂犬疫苗",
                            AppTime.today().toString(), due), token);
        }

        // 读消息中心会惰性补算提醒——这正是把「当时的档位」变成可断言的行为的入口
        assertThat(hasVaccineReminder(careToken)).as("照护宠应收到疫苗到期提醒（照护档 30 天）").isTrue();
        assertThat(hasVaccineReminder(normalToken)).as("普通宠不应收到（普通档 7 天）").isFalse();

        // 普通档阈值本身没变：到期日落到 5 天内时，普通宠照样提醒
        String soon = AppTime.today().plusDays(5).toString();
        api.post(BASE + normalPet + "/epidemic-records",
                new com.pethealth.api.app.EpidemicRecordRequest(2, "体内驱虫", AppTime.today().toString(), soon),
                normalToken);
        assertThat(hasReminder(normalToken, 2)).as("普通档的驱虫提醒（7 天内）必须照旧").isTrue();
    }

    // ---------------------------------------------------------------- 工具

    private boolean hasVaccineReminder(String token) {
        return hasReminder(token, 1);
    }

    private boolean hasReminder(String token, int type) {
        JsonNode messages = api.get("/api/v1/app/messages?page=1&page_size=50", token).data().path("list");
        for (JsonNode message : messages) {
            if (message.path("type").asInt() == type) {
                return true;
            }
        }
        return false;
    }

    private JsonNode dimension(JsonNode score, String key) {
        for (JsonNode node : score.path("dimensions")) {
            if (key.equals(node.path("key").asText())) {
                return node;
            }
        }
        throw new AssertionError("找不到维度：" + key + "，实际内容：" + score);
    }

    private JsonNode section(JsonNode sections, String code) {
        for (JsonNode node : sections) {
            if (code.equals(node.path("code").asText())) {
                return node;
            }
        }
        throw new AssertionError("找不到分项：" + code);
    }

    private String register(String phone) {
        return api.registerAndGetAccessToken(phone);
    }
}
