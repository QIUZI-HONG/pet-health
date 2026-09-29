package com.pethealth.boot.file;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethealth.api.app.FilePresignRequest;
import com.pethealth.boot.support.ApiClient;
import com.pethealth.boot.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件与对象存储切片（#95 的验收标准，决策见 ADR-0020）。
 *
 * <p>跑在真实 HTTP + 真实 MySQL + 真实本地盘上（ADR-0014）：上传路径的失败模式大多出在
 * 「字节怎么落、签名怎么对、越权怎么拦」这类只有真环境才暴露的地方。
 *
 * <p>测试用的图片是**现场生成的真图**（不是几个头字节的假文件）：假的 JPEG 过不了 ImageIO 解码，
 * 会让「魔数对了但内容坏了」这条被漏测。
 */
@DisplayName("文件与对象存储（切片 #95 / ADR-0020）")
class FileStorageTest extends IntegrationTestBase {

    private static final String PHONE_A = "13900001001";
    private static final String PHONE_B = "13900001002";

    @Autowired
    private ObjectMapper objectMapper;

    private ApiClient api;

    @BeforeEach
    void setUpClient() {
        api = new ApiClient(rest, objectMapper);
    }

    // ------------------------------------------------------------ 主链路

    @Test
    @DisplayName("直传落定：字节往返一致，并生成缩略图")
    void uploadAndReadBack() {
        String token = api.registerAndGetAccessToken(PHONE_A);
        long petId = api.createPet(token, "豆豆");

        JsonNode presign = presignOne(token, petId, "checkin", "image/png");
        long fileId = presign.path("file_id").asLong();
        String uploadUrl = presign.path("upload_url").asText();

        byte[] original = png(1200, 800);
        ApiClient.ApiCall uploaded = api.putBinary(uploadUrl, original);
        assertThat(uploaded.status()).as("直传应返回 204").isEqualTo(204);

        // 元数据：类型按魔数判定，宽高由服务端解析
        JsonNode view = api.get("/api/v1/app/files/" + fileId, token).data();
        assertThat(view.path("mime").asText()).isEqualTo("image/png");
        assertThat(view.path("size_bytes").asLong()).isEqualTo(original.length);
        assertThat(view.path("width").asInt()).isEqualTo(1200);
        assertThat(view.path("height").asInt()).isEqualTo(800);
        assertThat(view.path("biz_type").asText()).isEqualTo("checkin");

        // 原图读回：字节完全一致
        ResponseEntity<byte[]> read = api.getBinary(view.path("url").asText());
        assertThat(read.getStatusCode().value()).isEqualTo(200);
        assertThat(read.getBody()).isEqualTo(original);
        assertThat(read.getHeaders().getContentType().toString()).isEqualTo("image/png");
        assertThat(read.getHeaders().getCacheControl()).contains("private");

        // 缩略图：宽度收到 480，且是 JPEG（交付文档 13.2 的质量 0.8 在这里落地）
        ResponseEntity<byte[]> thumb = api.getBinary(view.path("thumb_url").asText());
        assertThat(thumb.getStatusCode().value()).isEqualTo(200);
        assertThat(thumb.getHeaders().getContentType().toString()).isEqualTo("image/jpeg");
        BufferedImage thumbImage = decode(thumb.getBody());
        assertThat(thumbImage.getWidth()).isEqualTo(480);
        assertThat(thumbImage.getHeight()).isEqualTo(320);
    }

    @Test
    @DisplayName("落定写操作留下 operator_id 与 trace_id（审计列）")
    void storeIsAudited() {
        String token = api.registerAndGetAccessToken(PHONE_A);
        long petId = api.createPet(token, "豆豆");
        long userId = jdbc.queryForObject("SELECT id FROM `user` WHERE is_deleted = 0", Long.class);

        JsonNode presign = presignOne(token, petId, "checkin", "image/png");
        long fileId = presign.path("file_id").asLong();
        String uploadUrl = presign.path("upload_url").asText();

        // 带上显式链路 ID：上传成功是 204 无响应体，没有信封里的 request_id 可以比对
        String uploadTraceId = "trace-upload-0001";
        assertThat(api.putBinary(uploadUrl, png(1200, 800), uploadTraceId).status()).isEqualTo(204);

        // 这三列是条件更新（`update(null, ...)`）最容易丢的东西：MyBatis-Plus 的
        // AuditMetaObjectHandler 只在实体非 null 时才跑，所以必须显式 set。
        // 原先丢了它们的表现是——presign 那行还是建凭证的 trace，落定这行没变，
        // 而缩略图（新 insert）却带着落定请求的 trace，同一个请求写出两套留痕。
        assertThat(jdbc.queryForObject(
                "SELECT trace_id FROM file_object WHERE id = ?", String.class, fileId))
                .isEqualTo(uploadTraceId);

        // updated_by 是 **0（系统写入）而不是建凭证的那个用户**：落定走的是 open 域的签名地址，
        // 不带登录态，所以 TraceIds.currentOperatorId() 按约定返回 SYSTEM_OPERATOR_ID。
        // 这条断言把这个事实钉住——将来谁想把「谁传的字节」记成登录用户，得先改这里。
        assertThat(jdbc.queryForObject(
                "SELECT updated_by FROM file_object WHERE id = ?", Long.class, fileId))
                .isZero();
        // 对照组：建凭证那一步是有登录态的，create_by 就是本人
        assertThat(jdbc.queryForObject(
                "SELECT created_by FROM file_object WHERE id = ?", Long.class, fileId))
                .isEqualTo(userId);
    }

    @Test
    @DisplayName("列表只出原图，缩略图不出现在列表里；删掉后读地址失效")
    void listAndDelete() {
        String token = api.registerAndGetAccessToken(PHONE_A);
        long petId = api.createPet(token, "豆豆");
        String uploadUrl = presignOne(token, petId, "care", "image/jpeg").path("upload_url").asText();
        api.putBinary(uploadUrl, jpeg(800, 600));

        JsonNode list = api.get("/api/v1/app/files?biz_type=care", token).data();
        assertThat(list).hasSize(1);
        assertThat(list.get(0).path("role").asText()).isEqualTo("original");
        long fileId = list.get(0).path("id").asLong();
        String url = list.get(0).path("url").asText();

        assertThat(api.delete("/api/v1/app/files/" + fileId, token).code()).isZero();
        assertThat(api.get("/api/v1/app/files?biz_type=care", token).data()).isEmpty();
        assertThat(api.get("/api/v1/app/files/" + fileId, token).code())
                .as("删掉的文件按不存在处理")
                .isEqualTo(40400);
        assertThat(api.getBinary(url).getStatusCode().value()).isEqualTo(404);
    }

    // ------------------------------------------------------------ 上传校验

    @Test
    @DisplayName("类型按魔数判定：声明 image/jpeg 但内容不是图片 → 拒绝")
    void mimeSniffedFromContent() {
        String token = api.registerAndGetAccessToken(PHONE_A);
        long petId = api.createPet(token, "豆豆");
        String uploadUrl = presignOne(token, petId, "profile", "image/jpeg").path("upload_url").asText();

        ApiClient.ApiCall call = api.putBinary(uploadUrl, "我不是图片".getBytes(StandardCharsets.UTF_8));

        assertThat(call.code()).isEqualTo(40001);
        assertThat(call.message()).contains("JPEG");
        assertThat(api.get("/api/v1/app/files?biz_type=profile", token).data())
                .as("被拒的文件不能留在列表里")
                .isEmpty();
    }

    @Test
    @DisplayName("一次最多 9 张")
    void tooManyFilesRejected() {
        String token = api.registerAndGetAccessToken(PHONE_A);
        long petId = api.createPet(token, "豆豆");

        List<FilePresignRequest.Item> items = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            items.add(new FilePresignRequest.Item("image/jpeg", null, null));
        }
        ApiClient.ApiCall call = api.post("/api/v1/app/files/presign",
                new FilePresignRequest("checkin", petId, items), token);

        assertThat(call.code()).isEqualTo(40001);
        assertThat(call.message()).contains("9");
    }

    @Test
    @DisplayName("同一凭证只能落定一次")
    void cannotUploadTwice() {
        String token = api.registerAndGetAccessToken(PHONE_A);
        long petId = api.createPet(token, "豆豆");
        String uploadUrl = presignOne(token, petId, "checkin", "image/png").path("upload_url").asText();

        assertThat(api.putBinary(uploadUrl, png(120, 80)).status()).isEqualTo(204);
        ApiClient.ApiCall again = api.putBinary(uploadUrl, png(120, 80));

        assertThat(again.code()).isEqualTo(40900);
    }

    // ------------------------------------------------------------ 签名与凭证

    @Test
    @DisplayName("篡改签名与伪造文件号都要被拒")
    void tamperedTokenRejected() {
        String token = api.registerAndGetAccessToken(PHONE_A);
        long petId = api.createPet(token, "豆豆");
        JsonNode presign = presignOne(token, petId, "checkin", "image/png");
        long fileId = presign.path("file_id").asLong();
        String uploadUrl = presign.path("upload_url").asText();

        String tampered = uploadUrl.substring(0, uploadUrl.length() - 1)
                + (uploadUrl.endsWith("0") ? "1" : "0");
        assertThat(api.putBinary(tampered, png(120, 80)).code()).isEqualTo(40001);

        // 换个文件号：签名里绑了 fileId，换号即无效
        String otherUrl = uploadUrl.replace("/files/" + fileId + "/", "/files/" + (fileId + 1) + "/");
        assertThat(api.putBinary(otherUrl, png(120, 80)).code()).isNotZero();
    }

    @Test
    @DisplayName("凭证过期后直传被拒")
    void expiredPresignRejected() {
        String token = api.registerAndGetAccessToken(PHONE_A);
        long petId = api.createPet(token, "豆豆");
        JsonNode presign = presignOne(token, petId, "checkin", "image/png");
        long fileId = presign.path("file_id").asLong();
        String uploadUrl = presign.path("upload_url").asText();

        // 直接把待传行的过期时间推到过去：等 30 分钟不现实，而这条路径（过期）必须被守住
        jdbc.update("UPDATE file_object SET expires_at = DATE_SUB(NOW(), INTERVAL 1 MINUTE) WHERE id = ?", fileId);

        ApiClient.ApiCall call = api.putBinary(uploadUrl, png(120, 80));
        assertThat(call.code()).isEqualTo(40001);
        assertThat(call.message()).contains("失效");
    }

    @Test
    @DisplayName("上传凭证不能当读凭证用（用途写进了签名）")
    void uploadTokenCannotRead() {
        String token = api.registerAndGetAccessToken(PHONE_A);
        long petId = api.createPet(token, "豆豆");
        JsonNode presign = presignOne(token, petId, "checkin", "image/png");
        long fileId = presign.path("file_id").asLong();
        String uploadUrl = presign.path("upload_url").asText();
        api.putBinary(uploadUrl, png(120, 80));

        String uploadToken = uploadUrl.substring(uploadUrl.indexOf("token=") + 6);
        ResponseEntity<byte[]> read = api.getBinary("/api/v1/open/files/" + fileId + "?token=" + uploadToken);

        assertThat(read.getStatusCode().value()).isEqualTo(404);
    }

    // ------------------------------------------------------------ 越权

    @Test
    @DisplayName("别人的文件按不存在处理（读 / 删 / 读地址都拦）")
    void crossUserAccessDenied() {
        String tokenA = api.registerAndGetAccessToken(PHONE_A);
        long petA = api.createPet(tokenA, "豆豆");
        String uploadUrlA = presignOne(tokenA, petA, "checkin", "image/png").path("upload_url").asText();
        api.putBinary(uploadUrlA, png(120, 80));
        long fileIdA = api.get("/api/v1/app/files", tokenA).data().get(0).path("id").asLong();
        String urlA = api.get("/api/v1/app/files", tokenA).data().get(0).path("url").asText();

        String tokenB = api.registerAndGetAccessToken(PHONE_B);

        assertThat(api.get("/api/v1/app/files/" + fileIdA, tokenB).code()).isEqualTo(40400);
        assertThat(api.delete("/api/v1/app/files/" + fileIdA, tokenB).code()).isEqualTo(40400);
        assertThat(api.get("/api/v1/app/files", tokenB).data()).isEmpty();
        // B 拿着 A 的读地址：读地址里是 A 的签名，B 能用它读到 A 的图吗？
        // 能——这就是「签名 URL」的语义（和对象存储的短时 URL 一样，谁拿到谁能读）。
        // 所以契约里写明「不要把 url 缓存/外传」，而归属校验守的是 id 型接口。
        assertThat(api.getBinary(urlA).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("未登录不能申请凭证，也不能列文件")
    void loginRequired() {
        assertThat(api.post("/api/v1/app/files/presign",
                new FilePresignRequest("checkin", null,
                        List.of(new FilePresignRequest.Item("image/jpeg", null, null))), null).code())
                .isEqualTo(40100);
        assertThat(api.get("/api/v1/app/files", null).code()).isEqualTo(40100);
    }

    // ------------------------------------------------------------ 辅助

    private JsonNode presignOne(String token, long petId, String bizType, String mime) {
        ApiClient.ApiCall call = api.post("/api/v1/app/files/presign",
                new FilePresignRequest(bizType, petId,
                        List.of(new FilePresignRequest.Item(mime, null, null))), token);
        assertThat(call.code()).as("申请凭证应当成功：" + call.body()).isZero();
        return call.data().get(0);
    }

    private static byte[] png(int width, int height) {
        return encode(image(width, height), "png");
    }

    private static byte[] jpeg(int width, int height) {
        return encode(image(width, height), "jpg");
    }

    private static BufferedImage image(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.decode("#0ea382"));
        g.fillRect(0, 0, width, height);
        g.dispose();
        return image;
    }

    private static byte[] encode(BufferedImage image, String format) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, format, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static BufferedImage decode(byte[] content) {
        try {
            BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(content));
            assertThat(image).as("响应体应当是一张能解码的图片").isNotNull();
            return image;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
