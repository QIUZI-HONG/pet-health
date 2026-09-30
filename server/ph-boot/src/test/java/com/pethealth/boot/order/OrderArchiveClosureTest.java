package com.pethealth.boot.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.pethealth.boot.support.ApiClient;
import com.pethealth.common.time.AppTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 服务者侧的照片墙直传 + 报工 → 健康档案回流（切片 #107 的最后一段；F023 的报工与 F004 的三源并存）。
 *
 * <p>三件必须钉住的事：
 *
 * <ol>
 *   <li><b>登录域</b>：服务者令牌能用文件直传（这正是本切片开工前堵住的地方——`/api/v1/app/files/**`
 *       只认 C 端令牌，门店的令牌在那里等于没登录），而 C 端令牌仍然进不去服务者的文件接口；
 *   <li><b>价值链路</b>：报工成功后 {@code archive_record} 里**真的有**一条 {@code source = 3}
 *       的服务记录（F004 的「手动 + AI + 服务者报工」三源），并且用户的时间轴读得到它；
 *   <li><b>硬约束仍然拦得住</b>：三个槽位缺一道就报不了工（ADR-0040 第四节），而且**档案里不会
 *       多出半条记录**——订单转「已完成」与档案那条记录是同一个事务（要么都成立，要么都不成立）。
 * </ol>
 */
@DisplayName("服务者侧：照片墙直传 + 报工 → 健康档案（切片 #107 / ADR-0040 / ADR-0030）")
class OrderArchiveClosureTest extends OrderTestSupport {

    @Test
    @DisplayName("服务者域令牌能直传 care 照片并挂到墙上；C 端令牌进不了服务者的文件接口")
    void providerDomainTokenCanUpload() {
        Scene scene = sceneInService();

        // 两个方向都不通，这才是本切片要补的那个洞：登录域按**路径前缀**判（ADR-0012）
        assertThat(api.post("/api/v1/app/files/presign", presignBody(scene.petId()), scene.shop().token()).code())
                .as("服务者令牌打 C 端文件接口：等于没登录")
                .isEqualTo(40100);
        assertThat(api.post("/api/v1/provider/files/presign", presignBody(scene.petId()), scene.user().token()).code())
                .as("C 端令牌打服务者文件接口：同样不通（跨端不通用）")
                .isEqualTo(40100);

        // 服务者侧：申请凭证 → 直传字节 → 落定（真实链路，不是造行）
        ApiClient.ApiCall presign = api.post("/api/v1/provider/files/presign",
                presignBody(scene.petId()), scene.shop().token());
        assertCodeOk(presign, "服务者侧申请上传凭证");
        long fileId = presign.data().get(0).path("file_id").asLong();
        String uploadUrl = presign.data().get(0).path("upload_url").asText();
        assertThat(api.putBinary(uploadUrl, jpeg(600, 400)).status()).isEqualTo(204);

        Map<String, Object> file = jdbc.queryForMap(
                "SELECT biz_type, pet_id, status, owner_user_id FROM file_object WHERE id = ?", fileId);
        assertThat(file.get("biz_type")).isEqualTo("care");
        assertThat(((Number) file.get("pet_id")).longValue()).isEqualTo(scene.petId());
        assertThat(((Number) file.get("status")).intValue()).as("直传即落定").isEqualTo(1);
        assertThat(((Number) file.get("owner_user_id")).longValue())
                .as("文件归属是**上传者**（门店的登录账号）——ADR-0020 的私有读按它判")
                .isEqualTo(providerUserId(scene.shop().providerId()));

        // 收窄的用途：服务者侧只收 care（契约里 biz_type 的枚举只有一个取值），别的用途 40001
        ApiClient.ApiCall otherPurpose = api.post("/api/v1/provider/files/presign",
                Map.of("biz_type", "profile", "pet_id", scene.petId(),
                        "items", List.of(Map.of("mime", "image/jpeg"))), scene.shop().token());
        assertThat(otherPurpose.code()).isEqualTo(40001);

        // 传完就能挂到槽位上（照片墙要求 care + 本单宠物的照片，见 ADR-0048）
        assertCodeOk(saveSlot(scene.shop().token(), scene.orderId(), 1, List.of(fileId), "接宠检查"), "挂到第一道");
    }

    @Test
    @DisplayName("报工成功：档案里真的多了一条「服务者报工」（source=3），用户的时间轴看得到")
    void reportWritesProviderSourcedArchiveRecord() {
        Scene scene = sceneInService();
        assertThat(archiveRows(scene.petId())).as("报工前：这只宠物的档案是干净的（用例库已清表）").isEmpty();

        fillWallAsProvider(scene);
        ApiClient.ApiCall reported = api.post("/api/v1/provider/orders/" + scene.orderId() + "/report",
                Map.of("remark", "洗护完成，皮肤无异常"), scene.shop().token());
        assertCodeOk(reported, "报工");
        assertThat(reported.data().path("status").asInt()).isEqualTo(3);

        List<Map<String, Object>> rows = archiveRows(scene.petId());
        assertThat(rows).as("报工成功必须写出一条档案记录（F004 的第三源）").hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(((Number) row.get("source")).intValue())
                .as("来源就是服务者报工——这条断言是本切片的价值证明")
                .isEqualTo(3);
        assertThat(((Number) row.get("user_id")).longValue())
                .as("记录挂在宠物主人名下（档案按宠物归属读，user_id 是审计那一列）")
                .isEqualTo(scene.user().userId());
        assertThat(((Number) row.get("category")).intValue())
                .as("落「其他指标」：打卡六项有幂等槽、防疫/证件/就医各有语义，见 ArchiveSectionService 的说明")
                .isEqualTo(10);
        assertThat(row.get("record_date").toString()).isEqualTo(AppTime.today().toString());
        assertThat(structuredPayloadOf(scene.petId())).contains("基础洗护（小型犬）").contains("洗护完成，皮肤无异常");
        assertThat(((Number) row.get("created_by")).longValue())
                .as("写操作留 operator_id（服务的门店账号）")
                .isEqualTo(providerUserId(scene.shop().providerId()));
        assertThat(row.get("trace_id").toString()).as("写操作留 trace_id").isNotBlank();

        // 用户侧：时间轴多了「服务者报工」那一条（ADR-0030 第四条的第四类事件），标题是服务项的名字
        JsonNode timeline = api.get("/api/v1/app/pets/" + scene.petId() + "/timeline?type=provider",
                scene.user().token()).data();
        assertThat(timeline.path("total").asLong()).isEqualTo(1);
        assertThat(timeline.path("list").get(0).path("type").asText()).isEqualTo("provider");
        assertThat(timeline.path("list").get(0).path("title").asText()).isEqualTo("基础洗护（小型犬）");
        assertThat(timeline.path("list").get(0).path("source").asInt()).isEqualTo(3);

        // 重复报工是状态冲突，不会写出第二条服务记录（一个订单只对应一条服务事实）
        assertThat(api.post("/api/v1/provider/orders/" + scene.orderId() + "/report", Map.of(), scene.shop().token())
                .code()).isEqualTo(40900);
        assertThat(archiveRows(scene.petId())).hasSize(1);
    }

    @Test
    @DisplayName("缺一道就报不了工：40900 点名缺哪道，档案里也不会多出半条记录")
    void blockedReportWritesNothing() {
        Scene scene = sceneInService();
        for (int slot : List.of(1, 2)) {
            long fileId = uploadCarePhotoAsProvider(scene.shop().token(), scene.petId());
            assertCodeOk(saveSlot(scene.shop().token(), scene.orderId(), slot, List.of(fileId), "第 " + slot + " 道"),
                    "保存槽位 " + slot);
        }

        ApiClient.ApiCall rejected = api.post("/api/v1/provider/orders/" + scene.orderId() + "/report",
                Map.of("remark", "先试试"), scene.shop().token());
        assertThat(rejected.code()).isEqualTo(40900);
        assertThat(rejected.message()).contains("取宠对比").doesNotContain("接宠检查");
        assertThat(archiveRows(scene.petId()))
                .as("订单与档案在同一个事务里：报工没成功，就不该留下一条孤儿记录")
                .isEmpty();

        // 补齐第三道 → 报工成功 → 这时才有一条，而且只有一条
        long last = uploadCarePhotoAsProvider(scene.shop().token(), scene.petId());
        assertCodeOk(saveSlot(scene.shop().token(), scene.orderId(), 3, List.of(last), "取宠对比"), "补齐第三道");
        assertCodeOk(api.post("/api/v1/provider/orders/" + scene.orderId() + "/report",
                Map.of("remark", "服务完成"), scene.shop().token()), "报工");
        assertThat(archiveRows(scene.petId())).hasSize(1);
    }

    // ---------------------------------------------------------------- 造场景与断言辅助

    /** 一个已经在「履约中」的场景：门店 + 主人 + 宠物 + 订单。 */
    private record Scene(Shop shop, UserActor user, long petId, long orderId) {
    }

    private Scene sceneInService() {
        Shop shop = createShop("LIC-ARCHIVE-001", "128.00");
        UserActor user = createUser(nextPhone());
        long petId = api.createPet(user.token(), "豆豆");
        long orderId = orderInService(user.token(), shop.token(), petId, shop, tomorrow(), "09:00");
        return new Scene(shop, user, petId, orderId);
    }

    private static Map<String, Object> presignBody(long petId) {
        return Map.of("biz_type", "care", "pet_id", petId, "items", List.of(Map.of("mime", "image/jpeg")));
    }

    /** 用**服务者令牌**直传一张 care 照片并返回 file_id。 */
    private long uploadCarePhotoAsProvider(String providerToken, long petId) {
        ApiClient.ApiCall presign = api.post("/api/v1/provider/files/presign", presignBody(petId), providerToken);
        assertCodeOk(presign, "服务者侧申请上传凭证");
        String uploadUrl = presign.data().get(0).path("upload_url").asText();
        long fileId = presign.data().get(0).path("file_id").asLong();
        if (api.putBinary(uploadUrl, jpeg(600, 400)).status() != 204) {
            throw new AssertionError("直传应当返回 204");
        }
        return fileId;
    }

    /** 三道照片墙各传一张——**全程走服务者令牌**（这条链路的验收标准）。 */
    private void fillWallAsProvider(Scene scene) {
        for (int slot = 1; slot <= 3; slot++) {
            long fileId = uploadCarePhotoAsProvider(scene.shop().token(), scene.petId());
            assertCodeOk(saveSlot(scene.shop().token(), scene.orderId(), slot, List.of(fileId), "第 " + slot + " 道"),
                    "保存照片槽位 " + slot);
        }
    }

    private List<Map<String, Object>> archiveRows(long petId) {
        return jdbc.queryForList("SELECT * FROM archive_record WHERE pet_id = ? AND is_deleted = 0 ORDER BY id", petId);
    }

    /** 分项载荷（JSON 列）：取成字符串再断言，免得依赖驱动返回的是 String 还是 byte[]。 */
    private String structuredPayloadOf(long petId) {
        return jdbc.queryForObject("SELECT CAST(structured_payload AS CHAR) FROM archive_record "
                + "WHERE pet_id = ? AND is_deleted = 0", String.class, petId);
    }

    /** 门店这个服务者绑定的登录账号 id（用来断言文件归属与 operator_id）。 */
    private long providerUserId(long providerId) {
        return jdbc.queryForObject("SELECT user_id FROM provider_user WHERE provider_id = ? AND status = 1 "
                + "AND is_deleted = 0 ORDER BY id LIMIT 1", Long.class, providerId);
    }
}
