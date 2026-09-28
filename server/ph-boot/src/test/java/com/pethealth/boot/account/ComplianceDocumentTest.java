package com.pethealth.boot.account;

import com.pethealth.boot.support.ApiClient;
import com.pethealth.boot.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 合规文档（切片 #74 的第一块）。
 *
 * <p>这里守的核心是**「占位不能被当成生效条款」**：正文在法务定稿前只是占位说明，
 * 而占位状态必须是接口里的一个字段（`is_placeholder`），不能只写在注释里——
 * 写在注释里前端就看不见，界面会把占位文字原样当成条款展示。
 */
@DisplayName("合规文档（切片 #74）")
class ComplianceDocumentTest extends IntegrationTestBase {

    private static final String PHONE = "13900003001";

    @Autowired
    private ObjectMapper objectMapper;

    private ApiClient api;

    @BeforeEach
    void setUpClient() {
        api = new ApiClient(rest, objectMapper);
    }

    @Test
    @DisplayName("三份文档都在，且都带占位标记与提示")
    void documentsAreListedAsPlaceholder() {
        String token = api.registerAndGetAccessToken(PHONE);

        var call = api.get("/api/v1/app/compliance/documents", token);

        assertThat(call.code()).isZero();
        assertThat(call.data()).hasSize(3);
        var codes = new java.util.ArrayList<String>();
        call.data().forEach(node -> codes.add(node.path("code").asText()));
        assertThat(codes).containsExactlyInAnyOrder("privacy_policy", "user_agreement", "ai_disclaimer");

        call.data().forEach(node -> {
            assertThat(node.path("is_placeholder").asBoolean())
                    .as(node.path("code").asText() + " 的正文仍是占位，必须如实标记")
                    .isTrue();
            assertThat(node.path("body").asText()).isNotBlank();
            assertThat(node.path("effective_from").isNull())
                    .as("占位文档没有生效日期——填了就等于声称它已生效")
                    .isTrue();
        });
    }

    @Test
    @DisplayName("单份文档可按 code 取；不存在的 code 按不存在处理")
    void singleDocumentAndUnknownCode() {
        String token = api.registerAndGetAccessToken(PHONE);

        var one = api.get("/api/v1/app/compliance/documents/ai_disclaimer", token);
        assertThat(one.code()).isZero();
        assertThat(one.data().path("title").asText()).isEqualTo("AI 免责声明");
        assertThat(one.data().path("body").asText()).contains("不是诊断结论");

        assertThat(api.get("/api/v1/app/compliance/documents/not_exists", token).code()).isEqualTo(40400);
    }

    @Test
    @DisplayName("未登录不能取文档")
    void loginRequired() {
        assertThat(api.get("/api/v1/app/compliance/documents", null).code()).isEqualTo(40100);
    }
}
