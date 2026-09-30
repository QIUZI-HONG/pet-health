package com.pethealth.boot.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.pethealth.boot.support.ApiClient;
import com.pethealth.boot.support.TestImages;
import com.pethealth.boot.support.TestUploads;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 资质材料传图（ADR-0053）。两件事要盯住：
 *
 * <ol>
 *   <li><b>图存的是 id、读地址当场签发</b>——所以它不会过期（把签名 URL 存库会），
 *       而且**审核员拿得到的地址能真的读出图**。审核员不是上传者，这一点是本次的关键：
 *       若走按归属过滤的 {@code FileUrlApi}，审核侧会拿到空地址，而那在界面上
 *       看起来就像「服务者没传材料」——一个会被误判成驳回理由的空值；
 *   <li><b>没有图不能提交</b>——材料图是审核的依据。
 * </ol>
 */
class QualificationImageTest extends ProviderApiTestSupport {

    @Test
    @DisplayName("上传 → 提交 → 服务者与审核员都拿到能打开的地址，字节逐一对得上")
    void imageReadableToBothSides() {
        ProviderActor actor = providerActor();
        byte[] bytes = TestImages.png(64, 64);
        long fileId = TestUploads.qualificationImage(api, actor.token(), bytes);

        // 请求体自己拼而不是用 application(...)：那样它会**再传一张自己的图**，
        // 于是断言比对的就不是我手里这个字节数组了（第一版就是这么错的）
        ApiClient.ApiCall submitted = api.post("/api/v1/provider/onboarding/applications",
                Map.of("name", "有图门店", "type", 1, "category", 1,
                        "address", "上海市测试路 1 号", "contact_phone", "13800007777",
                        "applicant_name", "张三",
                        "qualifications", List.of(Map.of(
                                "type", 1, "name", "营业执照", "cert_no", "LIC-IMG-001",
                                "file_id", fileId, "valid_until", "2030-12-31"))),
                actor.token());
        assertCodeOk(submitted, "提交带图的入驻申请");
        long applicationId = submitted.data().path("id").asLong();

        // 服务者看自己的申请：file_id 是库里那份引用（表单「整体替换」要靠它保住图），
        // file_url 是当场签发的读地址
        JsonNode mine = api.get("/api/v1/provider/onboarding/applications/" + applicationId, actor.token())
                .data().path("qualifications").get(0);
        assertThat(mine.path("file_id").asLong()).as("file_id 应当原样返回").isEqualTo(fileId);
        assertReadableImage(mine.path("file_url").asText(), bytes, "服务者侧");

        // 审核员看同一份申请：**他不是上传者**，但同样要能打开材料
        JsonNode forReview = api.get("/api/v1/admin/provider-applications/" + applicationId, adminToken())
                .data().path("qualifications").get(0);
        assertThat(forReview.path("file_id").asLong()).isEqualTo(fileId);
        assertReadableImage(forReview.path("file_url").asText(), bytes, "审核侧");
    }

    @Test
    @DisplayName("没有图的材料提交被拒（40001）")
    void imageIsRequired() {
        ProviderActor actor = providerActor();
        ApiClient.ApiCall rejected = api.post("/api/v1/provider/onboarding/applications",
                Map.of("name", "无图门店", "type", 1, "category", 1,
                        "address", "上海市测试路 2 号", "contact_phone", "13800008888",
                        "applicant_name", "李四",
                        "qualifications", List.of(Map.of(
                                "type", 1, "name", "营业执照", "cert_no", "LIC-IMG-NONE",
                                "valid_until", "2030-12-31"))),
                actor.token());

        assertThat(rejected.code()).as("缺图的材料应被拒：%s", rejected.body()).isEqualTo(40001);
        assertThat(rejected.message()).contains("上传");
    }

    @Test
    @DisplayName("补交材料同样要带图（服务者侧那条写路径）")
    void resubmitAlsoRequiresImage() {
        ApprovedProvider provider = createApprovedProvider("LIC-IMG-002");

        ApiClient.ApiCall rejected = api.put("/api/v1/provider/profile/qualifications",
                Map.of("qualifications", List.of(Map.of(
                        "type", 1, "name", "营业执照", "cert_no", "LIC-IMG-002-NEW",
                        "valid_until", "2030-12-31"))),
                provider.token());
        assertThat(rejected.code()).as("补交不带图也应被拒：%s", rejected.body()).isEqualTo(40001);

        // 带上图就通过，并且读得回来
        byte[] bytes = TestImages.png(80, 60);
        long fileId = TestUploads.qualificationImage(api, provider.token(), bytes);
        assertCodeOk(api.put("/api/v1/provider/profile/qualifications",
                Map.of("qualifications", List.of(Map.of(
                        "type", 1, "name", "营业执照（续期）", "cert_no", "LIC-IMG-002-NEW",
                        "file_id", fileId, "valid_until", "2030-12-31"))),
                provider.token()), "补交材料（带图）");

        JsonNode after = api.get("/api/v1/provider/onboarding/applications/"
                        + latestApplicationId(provider.token()), provider.token())
                .data().path("qualifications").get(0);
        assertThat(after.path("file_id").asLong()).isEqualTo(fileId);
        assertReadableImage(after.path("file_url").asText(), bytes, "补交后");
    }

    @Test
    @DisplayName("拿别人的 file_id 当自己的材料：40001（归属校验在服务者侧，ADR-0053 第二节）")
    void someoneElsesFileIsRejected() {
        ProviderActor owner = providerActor();
        long othersFileId = uploadWith(owner.token(), "qualification");

        ProviderActor attacker = providerActor();
        ApiClient.ApiCall rejected = api.post("/api/v1/provider/onboarding/applications",
                Map.of("name", "冒用门店", "type", 1, "category", 1,
                        "address", "上海市测试路 3 号", "contact_phone", "13800009999",
                        "applicant_name", "王五",
                        "qualifications", List.of(Map.of(
                                "type", 1, "name", "营业执照", "cert_no", "LIC-IMG-STOLEN",
                                "file_id", othersFileId, "valid_until", "2030-12-31"))),
                attacker.token());

        assertThat(rejected.code()).as("用别人的图应被拒：%s", rejected.body()).isEqualTo(40001);
        // 报错不区分「不存在 / 是别人的 / 用途不对」——分开说等于告诉他「这个 id 存在，只是不是你的」
        assertThat(rejected.message()).contains("本人上传");
    }

    @Test
    @DisplayName("本人上传但用途不是资质材料：40001（用途也要对得上）")
    void wrongBizTypeIsRejected() {
        ProviderActor actor = providerActor();
        long careImageId = uploadWith(actor.token(), "care");

        ApiClient.ApiCall rejected = api.post("/api/v1/provider/onboarding/applications",
                Map.of("name", "用途错配门店", "type", 1, "category", 1,
                        "address", "上海市测试路 4 号", "contact_phone", "13800001234",
                        "applicant_name", "赵六",
                        "qualifications", List.of(Map.of(
                                "type", 1, "name", "营业执照", "cert_no", "LIC-IMG-WRONGBIZ",
                                "file_id", careImageId, "valid_until", "2030-12-31"))),
                actor.token());

        assertThat(rejected.code()).as("用途不符的图应被拒：%s", rejected.body()).isEqualTo(40001);
    }

    @Test
    @DisplayName("补交材料同样不能用别人的图（服务者侧那条写路径也过同一道校验）")
    void resubmitRejectsSomeoneElsesFile() {
        ApprovedProvider provider = createApprovedProvider("LIC-IMG-003");
        long othersFileId = uploadWith(providerActor().token(), "qualification");

        ApiClient.ApiCall rejected = api.put("/api/v1/provider/profile/qualifications",
                Map.of("qualifications", List.of(Map.of(
                        "type", 1, "name", "营业执照", "cert_no", "LIC-IMG-003-NEW",
                        "file_id", othersFileId, "valid_until", "2030-12-31"))),
                provider.token());

        assertThat(rejected.code()).as("补交用别人的图应被拒：%s", rejected.body()).isEqualTo(40001);
        // 材料整批替换，校验在删除旧批**之前**：被拒时库里那份还在
        JsonNode still = api.get("/api/v1/provider/onboarding/applications/"
                        + latestApplicationId(provider.token()), provider.token())
                .data().path("qualifications");
        assertThat(still).as("被拒的提交不该动到已有材料").isNotEmpty();
    }

    /** 传一张图并指定用途（`qualification` / `care`），返回 `file_id`。 */
    private long uploadWith(String providerToken, String bizType) {
        byte[] bytes = TestImages.png(48, 48);
        JsonNode target = api.post("/api/v1/provider/files/presign",
                Map.of("biz_type", bizType, "items", List.of(Map.of("mime", "image/png"))),
                providerToken).data().get(0);
        assertThat(api.putBinary(target.path("upload_url").asText(), bytes).status())
                .as("直传应返回 204").isEqualTo(204);
        return target.path("file_id").asLong();
    }

    /** 服务者侧读材料的入口是「我的申请详情」，所以要先拿到申请单 id（一人一单）。 */
    private long latestApplicationId(String providerToken) {
        return api.get("/api/v1/provider/onboarding/applications?page=1&page_size=1", providerToken)
                .data().path("list").get(0).path("id").asLong();
    }
    /** 用视图给的地址把图取回来，并要求**字节与传上去的一模一样**。 */
    private void assertReadableImage(String readUrl, byte[] expected, String who) {
        assertThat(readUrl).as("%s应当拿到非空的读地址", who).isNotBlank();
        ResponseEntity<byte[]> read = api.getBinary(readUrl);
        assertThat(read.getStatusCode().value()).as("%s读图应成功", who).isEqualTo(200);
        assertThat(read.getBody()).as("%s读回的字节应与上传的一致", who).isEqualTo(expected);
        assertThat(read.getHeaders().getContentType().toString()).isEqualTo("image/png");
    }
}
