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
    @DisplayName("AI 免责声明不承诺「知识库」——检索层没接就不能这样写")
    void disclaimerDoesNotPromiseUnbuiltCapability() {
        String token = api.registerAndGetAccessToken(PHONE);

        String body = api.get("/api/v1/app/compliance/documents/ai_disclaimer", token)
                .data().path("body").asText();

        // 这份文案是**用户能读到的**：它基于宠物的健康档案与 AI 判断，
        // 但平台还没有知识库可检索（#100/#101），citations 恒空。V14 删掉了「公开知识库」那句承诺。
        // 接上检索后要改回这类措辞，先让这条用例红，并同时给出真正的来源。
        assertThat(body).doesNotContain("知识库");
        // 用正文自己的措辞断言（「无法替代兽医的面诊与检查」），别照抄另一条免责声明的用词——
        // 我第一版就抄成了「不能替代」，被这条用例当场抓到
        assertThat(body).contains("不是诊断结论").contains("无法替代").contains("兽医");
    }

    /**
     * 条款**注册前必须读得到**（交付文档 2.5 的合规口径，2026-09-30 验收的 F027）。
     *
     * <p>这条原先反过来断言「未登录不能取文档」——而登录页上就挂着「用户协议 / 隐私政策」
     * 两个链接，未登录点进去只会看到「需要先登录」。协议读不到，「我已阅读并同意」就没有依据。
     * 所以现在两份都是**免登录只读**（清单与详情），这条用例改成钉住放行。
     *
     * <p>同时钉住**边界**：写接口（注销、导出）仍然要登录——放行只对这两条 GET 生效，
     * 不是「/compliance 前缀一律放行」。
     */
    @Test
    @DisplayName("未登录也能读条款（清单与详情都放行），但账号操作仍要登录")
    void readableWithoutLogin() {
        assertThat(api.get("/api/v1/app/compliance/documents", null).code()).isEqualTo(0);
        assertThat(api.get("/api/v1/app/compliance/documents/user_agreement", null).code()).isEqualTo(0);
        // 未知编号在免登录路径上仍是 40400，不是 40100——免得写成「没登录就一律 401」而看不出参数错
        assertThat(api.get("/api/v1/app/compliance/documents/not_exists", null).code()).isEqualTo(40400);
        // 边界：账号操作照旧要登录（注销是 POST，不是 DELETE）
        assertThat(api.post("/api/v1/app/users/me/deactivation", null, null).code()).isEqualTo(40100);
    }
}
