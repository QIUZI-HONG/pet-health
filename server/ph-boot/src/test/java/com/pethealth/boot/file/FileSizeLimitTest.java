package com.pethealth.boot.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethealth.api.app.FilePresignRequest;
import com.pethealth.boot.support.ApiClient;
import com.pethealth.boot.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 体积上限的两道闸（切片 #95 / ADR-0020）。
 *
 * <p>单独一个测试类，因为要用**配置覆盖**把上限压到很小：真造一个 10MB 以上的图去测，会拖慢整个测试套，
 * 而这两条路径（申请时就声明超限、落定时实际超限）与上限的具体数值无关。上限压到 1KB 后，
 * 用一张几百 KB 的噪声图（纯色 PNG 会被压得很小，噪声才压不下去）就能真实触发。
 *
 * <p>代价是多一个 Spring 上下文（属性不同即新上下文），换来的是「上限真的被守住」有实测依据。
 *
 * <p>注解要把 {@code webEnvironment} 再写一遍：子类上的 {@code @SpringBootTest} 会**整体覆盖**基类那份，
 * 少写这一项就退回默认的 MOCK 环境，{@code TestRestTemplate} 随之消失（这个坑真踩过一次）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.file.max-bytes=1024")
@DisplayName("文件体积上限（切片 #95）")
class FileSizeLimitTest extends IntegrationTestBase {

    private static final String PHONE = "13900001003";

    @Autowired
    private ObjectMapper objectMapper;

    private ApiClient api;

    @BeforeEach
    void setUpClient() {
        api = new ApiClient(rest, objectMapper);
    }

    @Test
    @DisplayName("申请时就声明超出体积 → 直接拒绝，不给凭证")
    void presignRejectsOversizedDeclaration() {
        String token = api.registerAndGetAccessToken(PHONE);

        ApiClient.ApiCall call = api.post("/api/v1/app/files/presign",
                new FilePresignRequest("checkin", null,
                        List.of(new FilePresignRequest.Item("image/png", 2048L, null))), token);

        assertThat(call.code()).isEqualTo(40001);
        assertThat(call.message()).contains("MB");
    }

    @Test
    @DisplayName("落定时以实际字节数为准：声明不超、实际超了照样拒")
    void storeRejectsOversizedContent() {
        String token = api.registerAndGetAccessToken(PHONE);
        String uploadUrl = api.post("/api/v1/app/files/presign",
                        new FilePresignRequest("checkin", null,
                                List.of(new FilePresignRequest.Item("image/png", null, null))), token)
                .data().get(0).path("upload_url").asText();

        byte[] noisy = noisyPng(300, 300);
        assertThat(noisy.length).as("测试前提：噪声图必须大于 1KB 上限").isGreaterThan(1024);

        ApiClient.ApiCall call = api.putBinary(uploadUrl, noisy);

        assertThat(call.code()).isEqualTo(40001);
        assertThat(api.get("/api/v1/app/files", token).data()).isEmpty();
    }

    /** 逐像素随机噪声：纯色图会被 PNG 压到 1KB 以下，噪声才是稳定的「大文件」。 */
    private static byte[] noisyPng(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(42);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                image.setRGB(x, y, random.nextInt(0xFFFFFF));
            }
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
