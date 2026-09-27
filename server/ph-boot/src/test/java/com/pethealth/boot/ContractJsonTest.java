package com.pethealth.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethealth.api.app.PetCreateRequest;
import com.pethealth.api.app.RegisterRequest;
import com.pethealth.boot.support.ApiClient;
import com.pethealth.boot.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 契约对齐：**响应的字段名就是要契约里写的那些**。
 *
 * <p>为什么值得单独测一层：前端类型由契约生成，后端 DTO 手写（ADR-0005）。
 * 一旦有人把 {@code is_deleted} 写成 {@code deleted}、把金额从字符串改成数字，
 * TypeScript 那边不会报错（类型还是 string | number 之类），只会在运行时静默错位。
 * 这一层把「两边对不上」变成构建期红灯。
 */
class ContractJsonTest extends IntegrationTestBase {

    private ApiClient api;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUpClient() {
        api = new ApiClient(rest, objectMapper);
    }

    @Test
    @DisplayName("统一响应的信封字段是 code / message / data / request_id")
    void responseEnvelopeKeys() {
        String token = api.registerAndGetAccessToken("13800138201");

        JsonNode body = api.get("/api/v1/app/users/me", token).body();

        assertThat(body.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("code", "message", "data", "request_id");
    }

    @Test
    @DisplayName("令牌对的字段名与契约一致，且 expires_in 是数字")
    void tokenPairKeys() {
        JsonNode data = api.post("/api/v1/app/auth/register",
                new RegisterRequest("13800138202", ApiClient.DEFAULT_PASSWORD, null)).data();

        assertThat(data.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("access_token", "refresh_token", "expires_in", "user");
        assertThat(data.path("expires_in").isNumber()).isTrue();
    }

    @Test
    @DisplayName("UserProfile 的字段名与契约一致（含 active_pet_id 的 snake_case）")
    void userProfileKeys() {
        String token = api.registerAndGetAccessToken("13800138203");
        api.post("/api/v1/app/pets",
                new PetCreateRequest("豆豆", 1, null, 0, null, null, null, null, null, null), token);

        JsonNode user = api.get("/api/v1/app/users/me", token).data();

        assertThat(user.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "id", "phone", "nickname", "avatar", "gender", "active_pet_id", "created_at");
        assertThat(user.path("created_at").asText()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
    }

    @Test
    @DisplayName("Pet 的字段名与契约一致，is_ 前缀的字段没有被 Java 命名习惯改掉")
    void petKeys() {
        String token = api.registerAndGetAccessToken("13800138204");
        long petId = api.post("/api/v1/app/pets",
                        new PetCreateRequest("豆豆", 2, "英短", 2, LocalDate.of(2024, 1, 2), "4.05",
                                "https://example.com/a.png", true, false, null), token)
                .data().path("id").asLong();

        JsonNode pet = api.get("/api/v1/app/pets/" + petId, token).data();

        assertThat(pet.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "id", "name", "species", "breed", "gender", "birthday", "weight", "avatar",
                "is_sterilized", "is_chronic", "chronic_desc", "restorable_until", "created_at", "updated_at");
        // 布尔用 JSON 的 true/false，不是 1/0
        assertThat(pet.path("is_sterilized").isBoolean()).isTrue();
        assertThat(pet.path("is_chronic").isBoolean()).isTrue();
    }

    @Test
    @DisplayName("小数（体重）走字符串，避免 JS 丢精度；日期走约定格式")
    void decimalsAreStringsAndDatesUseProjectFormat() {
        String token = api.registerAndGetAccessToken("13800138205");
        long petId = api.post("/api/v1/app/pets",
                        new PetCreateRequest("豆豆", 1, null, 0, LocalDate.of(2020, 12, 31), "12.50",
                                null, null, null, null), token)
                .data().path("id").asLong();

        JsonNode pet = api.get("/api/v1/app/pets/" + petId, token).data();

        assertThat(pet.path("weight").isTextual()).isTrue();
        assertThat(pet.path("weight").asText()).isEqualTo("12.50");
        assertThat(pet.path("birthday").asText()).isEqualTo("2020-12-31");
        assertThat(pet.path("created_at").asText()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
    }

    @Test
    @DisplayName("日期字段名与值时区是东八区")
    void timestampsUseShanghaiZone() {
        String token = api.registerAndGetAccessToken("13800138206");
        long petId = api.post("/api/v1/app/pets",
                new PetCreateRequest("豆豆", 1, null, 0, null, null, null, null, null, null), token)
                .data().path("id").asLong();

        String createdAt = api.get("/api/v1/app/pets/" + petId, token).data().path("created_at").asText();
        String dbCreatedAt = jdbc.queryForObject("SELECT DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') FROM pet WHERE id = ?",
                String.class, petId);

        // 接口里的时间与库里存的时间逐个字符相同——两处都要是东八区，差 8 小时就是这里红的
        assertThat(createdAt).isEqualTo(dbCreatedAt);
    }

    @Test
    @DisplayName("回收站里的 restorable_until 是删除时间 + 30 天")
    void restorableUntilIsThirtyDaysAfterDeletion() {
        String token = api.registerAndGetAccessToken("13800138207");
        long petId = api.post("/api/v1/app/pets",
                new PetCreateRequest("豆豆", 1, null, 0, null, null, null, null, null, null), token)
                .data().path("id").asLong();
        api.delete("/api/v1/app/pets/" + petId, token);

        String restorableUntil = api.get("/api/v1/app/pets?deleted=true", token)
                .data().get(0).path("restorable_until").asText();

        java.time.LocalDateTime expected = jdbc.queryForObject(
                        "SELECT deleted_at FROM pet WHERE id = ?", java.time.LocalDateTime.class, petId)
                .plusDays(30);
        assertThat(restorableUntil).isEqualTo(expected.format(
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
    }

    @Test
    @DisplayName("契约里定义的字段集合与实现一致（漏字段/多字段都会在这里暴露）")
    void implementedFieldsMatchDocumentedSet() {
        // 这份清单来自 contract/app.yaml；改了契约就要改这里，等于强制一次人工比对
        Set<String> documentedPetFields = Set.of(
                "id", "name", "species", "breed", "gender", "birthday", "weight", "avatar",
                "is_sterilized", "is_chronic", "chronic_desc", "restorable_until", "created_at", "updated_at");
        Set<String> documentedUserFields = Set.of(
                "id", "phone", "nickname", "avatar", "gender", "active_pet_id", "created_at");

        String token = api.registerAndGetAccessToken("13800138208");
        long petId = api.post("/api/v1/app/pets",
                new PetCreateRequest("豆豆", 1, null, 0, null, null, null, null, null, null), token)
                .data().path("id").asLong();

        assertThat(api.get("/api/v1/app/pets/" + petId, token).data().fieldNames()).toIterable()
                .containsExactlyInAnyOrderElementsOf(documentedPetFields);
        assertThat(api.get("/api/v1/app/users/me", token).data().fieldNames()).toIterable()
                .containsExactlyInAnyOrderElementsOf(documentedUserFields);
    }

}
