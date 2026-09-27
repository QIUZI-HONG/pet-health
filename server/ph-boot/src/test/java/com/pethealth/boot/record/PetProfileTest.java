package com.pethealth.boot.record;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethealth.api.app.PetCreateRequest;
import com.pethealth.api.app.PetUpdateRequest;
import com.pethealth.api.app.RegisterRequest;
import com.pethealth.boot.support.ApiClient;
import com.pethealth.boot.support.IntegrationTestBase;
import com.pethealth.common.time.AppTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 宠物档案切片：建档 / 编辑 / 多宠 / 软删除与恢复 / 越权（切片 #94 的验收标准）。
 *
 * <p>重点盯三件事：**软删除的可恢复窗口**、**越权返回 40400 而不是 40300**、**审计字段真的写进去了**。
 */
class PetProfileTest extends IntegrationTestBase {

    private ApiClient api;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUpClient() {
        api = new ApiClient(rest, objectMapper);
    }

    @Test
    @DisplayName("建档：字段齐全，体重是两位小数的字符串")
    void createPetWithFullProfile() {
        String token = api.registerAndGetAccessToken("13800138001");

        ApiClient.ApiCall call = api.post("/api/v1/app/pets", new PetCreateRequest(
                "豆豆", 1, "柯基", 1, java.time.LocalDate.of(2023, 5, 1), "12.5",
                null, true, true, "髋关节发育不良"), token);

        assertThat(call.code()).isZero();
        JsonNode pet = call.data();
        assertThat(pet.path("name").asText()).isEqualTo("豆豆");
        assertThat(pet.path("species").asInt()).isEqualTo(1);
        assertThat(pet.path("breed").asText()).isEqualTo("柯基");
        assertThat(pet.path("weight").asText()).isEqualTo("12.50");
        assertThat(pet.path("birthday").asText()).isEqualTo("2023-05-01");
        assertThat(pet.path("is_sterilized").asBoolean()).isTrue();
        assertThat(pet.path("is_chronic").asBoolean()).isTrue();
        assertThat(pet.path("chronic_desc").asText()).isEqualTo("髋关节发育不良");
        assertThat(pet.path("restorable_until").isNull()).isTrue();
    }

    @Test
    @DisplayName("多宠：一个账号下多只，按时间倒序返回")
    void multiplePetsUnderOneAccount() {
        String token = api.registerAndGetAccessToken("13800138002");
        api.createPet(token, "豆豆");
        api.createPet(token, "咪咪");

        ApiClient.ApiCall list = api.get("/api/v1/app/pets", token);

        assertThat(list.code()).isZero();
        assertThat(list.data()).hasSize(2);
        assertThat(list.data().get(0).path("name").asText()).isEqualTo("咪咪");
        assertThat(list.data().get(1).path("name").asText()).isEqualTo("豆豆");
    }

    @Test
    @DisplayName("编辑：只改传了的字段，没传的保持原值")
    void updateOnlyTouchesProvidedFields() {
        String token = api.registerAndGetAccessToken("13800138003");
        long petId = api.createPet(token, "豆豆");

        ApiClient.ApiCall call = api.put("/api/v1/app/pets/" + petId,
                new PetUpdateRequest(null, null, "柴犬", null, null, "13.25", null, null, null, null), token);

        assertThat(call.code()).isZero();
        assertThat(call.data().path("breed").asText()).isEqualTo("柴犬");
        assertThat(call.data().path("weight").asText()).isEqualTo("13.25");
        assertThat(call.data().path("name").asText()).isEqualTo("豆豆");
        assertThat(call.data().path("species").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("删除是软删除：默认列表看不到，回收站看得到且带恢复截止时间")
    void deleteIsSoftAndVisibleInRecycleBin() {
        String token = api.registerAndGetAccessToken("13800138004");
        long petId = api.createPet(token, "豆豆");

        assertThat(api.delete("/api/v1/app/pets/" + petId, token).code()).isZero();

        assertThat(api.get("/api/v1/app/pets", token).data()).isEmpty();

        ApiClient.ApiCall recycleBin = api.get("/api/v1/app/pets?deleted=true", token);
        assertThat(recycleBin.data()).hasSize(1);
        assertThat(recycleBin.data().get(0).path("restorable_until").asText()).isNotBlank();

        // 行还在，只是标记为已删除——「物理删除仅限账号注销」（docs/conventions.md）
        Integer isDeleted = jdbc.queryForObject("SELECT is_deleted FROM pet WHERE id = ?", Integer.class, petId);
        LocalDateTime deletedAt = jdbc.queryForObject("SELECT deleted_at FROM pet WHERE id = ?",
                LocalDateTime.class, petId);
        assertThat(isDeleted).isEqualTo(1);
        assertThat(deletedAt).isNotNull();

        // 详情接口也当作不存在
        assertThat(api.get("/api/v1/app/pets/" + petId, token).code()).isEqualTo(40400);
    }

    @Test
    @DisplayName("30 天内可恢复")
    void restoreWithinWindow() {
        String token = api.registerAndGetAccessToken("13800138005");
        long petId = api.createPet(token, "豆豆");
        api.delete("/api/v1/app/pets/" + petId, token);

        ApiClient.ApiCall restored = api.post("/api/v1/app/pets/" + petId + "/restore", null, token);

        assertThat(restored.code()).isZero();
        assertThat(restored.data().path("name").asText()).isEqualTo("豆豆");
        assertThat(api.get("/api/v1/app/pets", token).data()).hasSize(1);
        assertThat(api.get("/api/v1/app/pets?deleted=true", token).data()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT is_deleted FROM pet WHERE id = ?", Integer.class, petId)).isZero();
    }

    @Test
    @DisplayName("超过 30 天恢复期：恢复失败，回收站里也不再出现")
    void restoreAfterWindowFails() {
        String token = api.registerAndGetAccessToken("13800138006");
        long petId = api.createPet(token, "豆豆");
        api.delete("/api/v1/app/pets/" + petId, token);

        // 直接把删除时间改成 31 天前，等价于「放了一个月才想起来」
        jdbc.update("UPDATE pet SET deleted_at = ? WHERE id = ?", AppTime.now().minusDays(31), petId);

        ApiClient.ApiCall restore = api.post("/api/v1/app/pets/" + petId + "/restore", null, token);
        assertThat(restore.status()).isEqualTo(404);
        assertThat(restore.code()).isEqualTo(40400);
        assertThat(api.get("/api/v1/app/pets?deleted=true", token).data()).isEmpty();
    }

    @Test
    @DisplayName("别人的宠物：读、改、删、恢复一律 40400，不泄露 id 是否存在")
    void otherUsersPetIsInvisible() {
        String ownerToken = api.registerAndGetAccessToken("13800138007");
        long petId = api.createPet(ownerToken, "豆豆");
        String intruderToken = api.registerAndGetAccessToken("13800138008");

        assertThat(api.get("/api/v1/app/pets/" + petId, intruderToken).code()).isEqualTo(40400);
        assertThat(api.put("/api/v1/app/pets/" + petId,
                new PetUpdateRequest("抢过来", null, null, null, null, null, null, null, null, null),
                intruderToken).code()).isEqualTo(40400);
        assertThat(api.delete("/api/v1/app/pets/" + petId, intruderToken).code()).isEqualTo(40400);
        assertThat(api.post("/api/v1/app/pets/" + petId + "/restore", null, intruderToken).code()).isEqualTo(40400);

        // 主人这边一切正常——确认上面失败不是因为宠物本身有问题
        assertThat(api.get("/api/v1/app/pets/" + petId, ownerToken).code()).isZero();
    }

    @Test
    @DisplayName("编辑时传空串表示清空（品种、头像）——库里也要真的清掉")
    void emptyStringClearsOptionalFields() {
        String token = api.registerAndGetAccessToken("13800138014");
        long petId = api.post("/api/v1/app/pets",
                        new PetCreateRequest("豆豆", 1, "柯基", 0, null, null, "https://example.com/a.png",
                                null, null, null), token)
                .data().path("id").asLong();

        ApiClient.ApiCall call = api.put("/api/v1/app/pets/" + petId, Map.of("breed", "", "avatar", ""), token);

        assertThat(call.code()).isZero();
        assertThat(call.data().path("breed").isNull()).isTrue();
        assertThat(call.data().path("avatar").isNull()).isTrue();
        // 响应说清了没用，库里也得清——MyBatis-Plus 默认忽略 null 字段，这条专门盯它
        assertThat(jdbc.queryForObject("SELECT breed FROM pet WHERE id = ?", String.class, petId)).isNull();
        assertThat(jdbc.queryForObject("SELECT avatar FROM pet WHERE id = ?", String.class, petId)).isNull();
    }

    @Test
    @DisplayName("切换当前宠物；宠物被删掉后当前宠物读出来是 null")
    void activePetSwitchAndStaleValue() {
        String token = api.registerAndGetAccessToken("13800138009");
        long first = api.createPet(token, "豆豆");
        long second = api.createPet(token, "咪咪");

        ApiClient.ApiCall switched = api.put("/api/v1/app/users/me/active-pet", Map.of("pet_id", second), token);
        assertThat(switched.code()).isZero();
        assertThat(switched.data().path("active_pet_id").asLong()).isEqualTo(second);
        assertThat(api.get("/api/v1/app/users/me", token).data().path("active_pet_id").asLong()).isEqualTo(second);

        // 删掉当前宠物：库里还留着 id，但读出来必须是 null（前端回退到列表第一只）
        api.delete("/api/v1/app/pets/" + second, token);
        assertThat(api.get("/api/v1/app/users/me", token).data().path("active_pet_id").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT active_pet_id FROM `user` WHERE is_deleted = 0", Long.class))
                .isEqualTo(second);

        // 切回第一只
        assertThat(api.put("/api/v1/app/users/me/active-pet", Map.of("pet_id", first), token)
                .data().path("active_pet_id").asLong()).isEqualTo(first);
    }

    @Test
    @DisplayName("切到别人的宠物返回 40400")
    void cannotActivateOtherUsersPet() {
        String ownerToken = api.registerAndGetAccessToken("13800138010");
        long petId = api.createPet(ownerToken, "豆豆");
        String otherToken = api.registerAndGetAccessToken("13800138011");

        ApiClient.ApiCall call = api.put("/api/v1/app/users/me/active-pet", Map.of("pet_id", petId), otherToken);

        assertThat(call.status()).isEqualTo(404);
        assertThat(call.code()).isEqualTo(40400);
    }

    @Test
    @DisplayName("写操作留下 operator_id 与 trace_id")
    void writesAreAudited() {
        String token = api.registerAndGetAccessToken("13800138012");
        long userId = jdbc.queryForObject("SELECT id FROM `user` WHERE is_deleted = 0", Long.class);

        ApiClient.ApiCall created = api.post("/api/v1/app/pets",
                new PetCreateRequest("豆豆", 2, null, 0, null, null, null, null, null, null), token);
        long petId = created.data().path("id").asLong();

        assertThat(jdbc.queryForObject("SELECT created_by FROM pet WHERE id = ?", Long.class, petId))
                .isEqualTo(userId);
        assertThat(jdbc.queryForObject("SELECT trace_id FROM pet WHERE id = ?", String.class, petId))
                .isEqualTo(created.requestId());

        ApiClient.ApiCall updated = api.put("/api/v1/app/pets/" + petId,
                new PetUpdateRequest("豆豆子", null, null, null, null, null, null, null, null, null), token);

        assertThat(jdbc.queryForObject("SELECT updated_by FROM pet WHERE id = ?", Long.class, petId))
                .isEqualTo(userId);
        assertThat(jdbc.queryForObject("SELECT trace_id FROM pet WHERE id = ?", String.class, petId))
                .isEqualTo(updated.requestId());
    }

    @Test
    @DisplayName("traceId 跟随调用方传入的 X-Request-Id")
    void traceIdFollowsIncomingHeader() {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.set("X-Request-Id", "trace-from-client-001");

        org.springframework.http.ResponseEntity<String> response = rest.postForEntity(
                "/api/v1/app/auth/register",
                new org.springframework.http.HttpEntity<>(new RegisterRequest("13800138013", ApiClient.DEFAULT_PASSWORD, null), headers),
                String.class);

        assertThat(response.getHeaders().getFirst("X-Request-Id")).isEqualTo("trace-from-client-001");
        assertThat(response.getBody()).contains("\"request_id\":\"trace-from-client-001\"");
    }


}
