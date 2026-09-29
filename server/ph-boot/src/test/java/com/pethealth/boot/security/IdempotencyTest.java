package com.pethealth.boot.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethealth.api.app.PetCreateRequest;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 写接口幂等键（ADR-0028）。
 *
 * <p>三条断言各自钉住一件事：
 *
 * <ol>
 *   <li><b>同一个键重放不再执行</b>：返回第一次的响应，且**副作用只发生一次**——这是幂等的定义，
 *       也是「AI 咨询重试可能多花一次钱」那类风险的解法。
 *   <li><b>同一个键换请求体要被拒</b>：否则客户端拿旧键发新请求会拿回上一次的答案，
 *       比报错危险得多（看起来成功、内容却是别人的）。
 *   <li><b>不带这个头就完全不变</b>：幂等是**可选**的（客户端主动声明），
 *       不能改掉所有现有调用方的行为——这条最容易在实现时被忽略。
 * </ol>
 */
@DisplayName("写接口幂等（ADR-0028）")
class IdempotencyTest extends IntegrationTestBase {

    private static final String PHONE = "13900004001";

    @Autowired
    private ObjectMapper objectMapper;

    private ApiClient api;

    @BeforeEach
    void setUpClient() {
        api = new ApiClient(rest, objectMapper);
    }

    /** 带 Idempotency-Key 发一次建宠请求。 */
    private ResponseEntity<String> createPet(String token, String key, String name) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        if (key != null) {
            headers.set("Idempotency-Key", key);
        }
        return rest.exchange(rest.getRootUri() + "/api/v1/app/pets", HttpMethod.POST,
                new HttpEntity<>(new PetCreateRequest(name, 1, null, 0, null, null, null, null, null, null),
                        headers),
                String.class);
    }

    private int petCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM pet", Integer.class);
    }

    @Test
    @DisplayName("同一个键重放：返回第一次的响应，且不再执行（只建出一只宠物）")
    void replayReturnsFirstResponseWithoutReExecuting() {
        String token = api.registerAndGetAccessToken(PHONE);

        ResponseEntity<String> first = createPet(token, "key-pet-create-1", "豆豆");
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(first.getBody()).contains("豆豆");
        assertThat(petCount()).isEqualTo(1);

        ResponseEntity<String> replay = createPet(token, "key-pet-create-1", "豆豆");

        assertThat(replay.getStatusCode().value()).as("重放仍返回 200").isEqualTo(200);
        assertThat(replay.getBody()).as("返回的是第一次那个响应").isEqualTo(first.getBody());
        assertThat(petCount()).as("副作用只发生一次——这才是幂等").isEqualTo(1);
    }

    @Test
    @DisplayName("同一个键换请求体：拒掉，不能把旧响应当成新请求的答案")
    void sameKeyWithDifferentBodyIsRejected() {
        String token = api.registerAndGetAccessToken(PHONE);
        assertThat(createPet(token, "key-pet-create-2", "豆豆").getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> mismatch = createPet(token, "key-pet-create-2", "完全不同的名字");

        assertThat(mismatch.getStatusCode().value()).isEqualTo(400);
        assertThat(mismatch.getBody()).contains("40001").contains("幂等");
        assertThat(petCount()).as("被拒的那次不能建出宠物").isEqualTo(1);
    }

    @Test
    @DisplayName("4xx 也回放：确定性的失败不必重跑一遍（ADR-0028 规则 3）")
    void clientErrorIsReplayed() {
        String token = api.registerAndGetAccessToken(PHONE);

        // 名字为空 → 参数校验失败（40001）。这类失败是确定性的，重跑也是同样结果
        ResponseEntity<String> first = createPet(token, "key-pet-create-4xx", "");
        assertThat(first.getStatusCode().value()).isEqualTo(400);
        assertThat(first.getBody()).contains("40001");

        ResponseEntity<String> replay = createPet(token, "key-pet-create-4xx", "");

        assertThat(replay.getStatusCode().value()).isEqualTo(400);
        assertThat(replay.getBody()).as("回放的是同一次失败").isEqualTo(first.getBody());
        assertThat(petCount()).isZero();
    }

    @Test
    @DisplayName("不带 Idempotency-Key：行为完全不变（两次请求建出两只）")
    void withoutHeaderNothingChanges() {
        String token = api.registerAndGetAccessToken(PHONE);

        assertThat(createPet(token, null, "豆豆").getStatusCode().value()).isEqualTo(200);
        assertThat(createPet(token, null, "豆豆二号").getStatusCode().value()).isEqualTo(200);

        assertThat(petCount()).as("幂等是可选能力，不改变现有调用方的语义").isEqualTo(2);
    }
}
