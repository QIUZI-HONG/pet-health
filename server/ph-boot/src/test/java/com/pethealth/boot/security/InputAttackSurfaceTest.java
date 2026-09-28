package com.pethealth.boot.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethealth.api.app.LoginRequest;
import com.pethealth.api.app.PetCreateRequest;
import com.pethealth.api.app.PetUpdateRequest;
import com.pethealth.boot.support.ApiClient;
import com.pethealth.boot.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 输入攻击面（交付文档 9.4 安全测试的工程可测部分）。
 *
 * <p>越权已经在各个模块的测试里逐个接口覆盖了（换 id 一律 40400），这里补的是**输入形状**：
 * SQL 注入形状的字符串、XSS 载荷、路径穿越、超长输入，以及「响应能不能被浏览器当 HTML 执行」。
 *
 * <p>这些点大多是「设计上就对」的（MyBatis 参数化、只回 JSON、不拼 SQL），但**没人验过**——
 * 而「没人验过」与「验过通过」在验收场合是两回事。这些用例的价值是：把「应该安全」变成红灯守卫，
 * 谁哪天改成字符串拼 SQL、或者给响应加上 text/html，这里会先红。
 *
 * <p>**不做的**：真正的渗透测试（模糊测试、依赖漏洞扫描、TLS 配置）——那需要外部工具与真实部署，
 * 属 #121/#122 的范围。
 */
@DisplayName("输入攻击面（注入 / XSS / 穿越 / 超长）")
class InputAttackSurfaceTest extends IntegrationTestBase {

    /** 一眼能认出来的注入形状：真被拼进 SQL 会直接报语法错或被当成恒真条件。 */
    private static final List<String> INJECTION_SHAPES = List.of(
            "' OR '1'='1",
            "'; DROP TABLE pet; --",
            "1 OR 1=1",
            "\" OR \"\"=\"",
            "admin'--",
            "%' UNION SELECT phone_enc, password_hash FROM `user` --");

    private static final String XSS_PAYLOAD = "<script>alert('xss')</script><img src=x onerror=alert(1)>";

    @Autowired
    private ObjectMapper objectMapper;

    private ApiClient api;

    @BeforeEach
    void setUpClient() {
        api = new ApiClient(rest, objectMapper);
    }

    @Test
    @DisplayName("注入形状的字符串当数据存：不报错、不改表、原样读回（MyBatis 参数化的红灯守卫）")
    void injectionShapesAreStoredAsData() {
        String token = api.registerAndGetAccessToken("13700005001");
        long petId = api.createPet(token, "豆豆");

        for (String shape : INJECTION_SHAPES) {
            // 建档：名字就是注入形状
            ApiClient.ApiCall created = api.post("/api/v1/app/pets",
                    new PetCreateRequest(shape, 1, null, 0, null, null, null, null, null, null), token);
            assertThat(created.status()).as("注入形状不该让建档失败：%s", shape).isEqualTo(200);

            // 读回来必须**逐字相等**：被截断 / 被转义 / 被解释成 SQL 都会在这里露馅
            long id = created.data().path("id").asLong();
            assertThat(api.get("/api/v1/app/pets/" + id, token).data().path("name").asText()).isEqualTo(shape);

            // 回滚测试数据形状：删掉，避免污染后续断言
            assertThat(api.delete("/api/v1/app/pets/" + id, token).status()).isEqualTo(200);
        }

        // 表还在（DROP TABLE 那句真的执行的话，后面这句就查不到了）
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pet WHERE id = ?", Integer.class, petId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `user`", Integer.class)).isGreaterThan(0);
    }

    @Test
    @DisplayName("注入形状的查询参数与登录体：一律参数错误或正常失败，不做字符串拼接")
    void injectionShapesInParamsAndLogin() {
        // 登录手机号是注入形状：**凭证校验失败**（40100「手机号或密码不正确」），
        // 既不是登录成功、也不是 500——注入形状在这条路径上就只是「一个查不到的手机号」
        ApiClient.ApiCall login = api.post("/api/v1/app/auth/login",
                new LoginRequest("' OR 1=1 --", "Passw0rd123"));
        assertThat(login.code()).as("注入形状不该绕过凭证校验：" + login.body()).isEqualTo(40100);

        // 路径参数是数字：形状不对直接参数错误，不会被拼进 SQL
        String token = api.registerAndGetAccessToken("13700005002");
        assertThat(api.get("/api/v1/app/pets/1 OR 1=1", token).status()).isEqualTo(400);

        // 查询参数里的注入形状：当作普通值处理（没有匹配 → 空列表，不是语法错）
        ApiClient.ApiCall listed = api.get("/api/v1/app/files?biz_type=ai_consult' OR '1'='1", token);
        assertThat(listed.code()).isEqualTo(0);
        assertThat(listed.data()).isEmpty();
    }

    @Test
    @DisplayName("XSS 载荷当数据存：接口只回 JSON（浏览器不会把响应当 HTML 执行）")
    void xssPayloadIsDataAndResponsesAreJson() {
        String token = api.registerAndGetAccessToken("13700005003");
        long petId = api.createPet(token, "豆豆");

        ApiClient.ApiCall updated = api.put("/api/v1/app/pets/" + petId,
                new PetUpdateRequest(XSS_PAYLOAD, null, null, null, null, null, null, null, null, null), token);
        assertThat(updated.code()).isZero();
        // 载荷原样存、原样回：转义是**前端**的职责（Vue 的 {{ }} 默认转义，全项目零 v-html），
        // 后端在这里二次转义反而会让用户看到 &lt;script&gt; 这种字面量
        assertThat(updated.data().path("name").asText()).isEqualTo(XSS_PAYLOAD);

        ResponseEntity<String> raw = rest.exchange("/api/v1/app/pets/" + petId, HttpMethod.GET,
                new HttpEntity<>(bearer(token)), String.class);
        assertThat(raw.getHeaders().getContentType())
                .as("响应必须是 application/json——content-type 说是 HTML，浏览器才会去执行脚本")
                .isNotNull();
        assertThat(raw.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON))
                .isTrue();
    }

    @Test
    @DisplayName("读图地址：签名即授权——篡改一律 40400，正确签名才拿得到字节")
    void fileReadRequiresValidSignature() {
        String token = api.registerAndGetAccessToken("13700005004");

        // 先走一遍正规链路：申请凭证 → 直传 → 拿签名读地址
        byte[] png = minimalPng();
        ApiClient.ApiCall presign = api.post("/api/v1/app/files/presign",
                Map.of("biz_type", "ai_consult", "items",
                        List.of(Map.of("mime", "image/png", "size_bytes", png.length))), token);
        assertThat(presign.code()).isZero();
        long fileId = presign.data().get(0).path("file_id").asLong();
        String uploadUrl = presign.data().get(0).path("upload_url").asText();

        ResponseEntity<String> uploaded = rest.exchange(uploadUrl, HttpMethod.PUT,
                new HttpEntity<>(png, jsonHeaders()), String.class);
        assertThat(uploaded.getStatusCode().value())
                .as("直传应落定，实际 %s：%s", uploaded.getStatusCode().value(), uploaded.getBody())
                .isEqualTo(204);

        // 读地址：列表接口给的就是签名地址（这正是 `<img src>` 用的那个）
        String readUrl = api.get("/api/v1/app/files?biz_type=ai_consult", token)
                .data().get(0).path("url").asText();
        assertThat(readUrl).startsWith("/api/v1/open/files/");

        ResponseEntity<byte[]> ok = rest.exchange(readUrl, HttpMethod.GET, HttpEntity.EMPTY, byte[].class);
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(ok.getBody()).as("签名读地址拿到的必须是原字节").isEqualTo(png);

        // 完全不带 token 参数：缺必填参数 → 400（连「有没有这个文件」都不谈）
        ResponseEntity<String> noParam = rest.exchange("/api/v1/open/files/" + fileId,
                HttpMethod.GET, HttpEntity.EMPTY, String.class);
        assertThat(noParam.getStatusCode().value()).as("不带签名应是参数错误").isEqualTo(400);

        // 带签名但无效（空串、乱造、路径穿越形状、超长）：一律 40400——
        // 按设计**不告诉调用方文件存不存在**，所以不给 400/403（那是「这个 id 是真的」的泄漏）
        for (String bad : List.of("", "up.9999999999.deadbeef", "../etc/passwd", "x".repeat(200))) {
            ResponseEntity<String> response = rest.exchange(
                    "/api/v1/open/files/" + fileId + "?token=" + bad,
                    HttpMethod.GET, HttpEntity.EMPTY, String.class);
            assertThat(response.getStatusCode().value())
                    .as("签名无效必须按不存在处理：token=%s", bad)
                    .isEqualTo(404);
        }
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_PNG);
        return headers;
    }

    /**
     * 一张真的 PNG（用 ImageIO 生成，不手写 base64）。
     *
     * 手写 base64 试过一次：字节不对，落定处按设计拒绝了（「只支持 JPEG 与 PNG，且文件需能正常打开」）
     * ——判定的确是魔数 + 能不能解码，这条用例顺手把那个行为也钉住了。
     */
    private byte[] minimalPng() {
        try {
            var image = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, 0xFF0000);
            var out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("生成测试图片失败", e);
        }
    }

    @Test
    @DisplayName("超长输入：字段长度校验先拦（不落库、不报 500）")
    void oversizedInputIsRejected() {
        String token = api.registerAndGetAccessToken("13700005005");

        ApiClient.ApiCall tooLongName = api.post("/api/v1/app/pets",
                new PetCreateRequest("啊".repeat(500), 1, null, 0, null, null, null, null, null, null), token);
        assertThat(tooLongName.status()).isEqualTo(400);
        assertThat(tooLongName.code()).isEqualTo(40001);

        // 问题描述同样有上限（500 字，契约里写了）
        long petId = api.createPet(token, "豆豆");
        ApiClient.ApiCall tooLongQuestion = api.post("/api/v1/app/pets/" + petId + "/ai-consults",
                Map.of("question", "啊".repeat(2000)), token);
        assertThat(tooLongQuestion.status()).isEqualTo(400);
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
