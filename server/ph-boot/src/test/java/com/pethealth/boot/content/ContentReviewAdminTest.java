package com.pethealth.boot.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.pethealth.boot.support.ApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运营后台的内容审核（切片 #84 / F020；权限见 ADR-0037 第一节「内容审核归运营」）。
 *
 * <p>三块：队列（按类型分别看）、处置（通过 / 驳回 / 下架，非法迁移 40900）、
 * 敏感词表（**在库里可维护**，ADR-0010 的业务可调项分层）。
 *
 * <p>队列里必须看得见 `author_id`：审核是有处置权的动作，看不见对象就没法处置——
 * 这与 C 端卡片恒匿名不矛盾，两者回答的是不同问题（谁能看到什么）。
 */
@DisplayName("社区 · 内容审核（运营后台，#84 / F020）")
class ContentReviewAdminTest extends CommunityTestSupport {

    @Test
    @DisplayName("审核队列：按类型分别看、默认只给待审、待审的通过后从队列消失")
    void queueIsPerTypeAndDefaultPending() {
        Actor author = postingActor();
        long petId = createPet(author, "旺财");
        long cardId = cardOk(author, petId, "卡片标题", "卡片正文");
        long questionId = questionOk(author, "问题标题", "问题正文");
        assertOk(approveContent(2, questionId), "先让提问过审（未过审的提问不能被回答）");
        long answerId = answerOk(author, questionId, "回答正文");

        // 三种类型各自的待审队列
        ApiClient.ApiCall cards = api.get("/api/v1/admin/community/contents?content_type=1", adminToken());
        assertOk(cards, "卡片队列");
        assertThat(cards.data().path("total").asInt()).isEqualTo(1);
        JsonNode cardRow = cards.data().path("list").get(0);
        assertThat(cardRow.path("content_type").asInt()).isEqualTo(1);
        assertThat(cardRow.path("content_type_name").asText()).isEqualTo("经验卡片");
        assertThat(cardRow.path("content_id").asLong()).isEqualTo(cardId);
        assertThat(cardRow.path("author_id").asLong()).isEqualTo(author.userId());
        assertThat(cardRow.path("status").asInt()).isZero();
        assertThat(cardRow.path("content").asText()).isEqualTo("卡片正文");

        assertThat(api.get("/api/v1/admin/community/contents?content_type=2", adminToken())
                .data().path("total").asInt()).isZero();
        ApiClient.ApiCall answers = api.get("/api/v1/admin/community/contents?content_type=3", adminToken());
        assertThat(answers.data().path("total").asInt()).isEqualTo(1);
        assertThat(answers.data().path("list").get(0).path("question_id").asLong()).isEqualTo(questionId);

        // content_type 必填（跨三张表 UNION 换不来什么，见契约说明）
        assertCode(api.get("/api/v1/admin/community/contents", adminToken()), 40001, 400, "不传内容类型");
        assertCode(api.get("/api/v1/admin/community/contents?content_type=9", adminToken()),
                40001, 400, "内容类型 9");
        assertCode(api.get("/api/v1/admin/community/contents?content_type=1&status=9", adminToken()),
                40001, 400, "状态 9");

        // 按作者过滤：别人发的看不到
        Actor other = postingActor();
        long otherCard = cardOk(other, createPet(other, "别的狗"), "别人的卡片", "正文");
        assertThat(api.get("/api/v1/admin/community/contents?content_type=1&author_id=" + author.userId(),
                adminToken()).data().path("total").asInt()).isEqualTo(1);
        assertThat(api.get("/api/v1/admin/community/contents?content_type=1&author_id=" + other.userId(),
                adminToken()).data().path("total").asInt()).isEqualTo(1);

        // 通过后从待审队列消失、出现在已发布队列里（别人的那张还在待审）
        assertOk(approveContent(1, cardId), "通过卡片");
        assertThat(api.get("/api/v1/admin/community/contents?content_type=1", adminToken())
                .data().path("total").asInt()).isEqualTo(1);
        ApiClient.ApiCall published = api.get("/api/v1/admin/community/contents?content_type=1&status=1",
                adminToken());
        assertThat(published.data().path("total").asInt()).isEqualTo(1);
        assertThat(published.data().path("list").get(0).path("content_id").asLong()).isEqualTo(cardId);

        // 回答数只数已过审的：这条回答还没过审，提问的 answer_count 是 0
        assertThat(api.get("/api/v1/app/community/questions/" + questionId, author.token())
                .data().path("answer_count").asInt()).isZero();

        // 队列分页（与 C 端同一套边界）
        assertCode(api.get("/api/v1/admin/community/contents?content_type=1&page=0", adminToken()),
                40001, 400, "页码 0");
        assertThat(api.get("/api/v1/admin/community/contents?content_type=1&page=9&page_size=20", adminToken())
                .data().path("list")).isEmpty();

        // 下架：理由必填（缺失 → 40001）
        assertCode(api.post("/api/v1/admin/community/contents/1/" + otherCard + "/reject",
                Map.of(), adminToken()), 40001, 400, "驳回不带理由");
        assertCode(api.post("/api/v1/admin/community/contents/1/" + otherCard + "/reject",
                Map.of("reason", "   "), adminToken()), 40001, 400, "驳回理由是空白");
    }

    @Test
    @DisplayName("审核接口的登录域与越权：C 端令牌与未登录一律 40100")
    void adminEndpointsRequireAdminDomain() {
        Actor appUser = postingActor();
        assertCode(api.get("/api/v1/admin/community/contents?content_type=1", appUser.token()),
                40100, 401, "C 端令牌调审核队列");
        assertCode(api.get("/api/v1/admin/community/contents?content_type=1", null),
                40100, 401, "未登录调审核队列");
        assertCode(api.get("/api/v1/admin/community/sensitive-words", appUser.token()),
                40100, 401, "C 端令牌调词表");
    }

    @Test
    @DisplayName("敏感词表：库里可维护（新增 / 停用 / 改词），重复与不存在都有明确码")
    void sensitiveWordsAreMaintainable() {
        String word = "测试词" + System.nanoTime();
        ApiClient.ApiCall created = addSensitiveWord(word);
        assertOk(created, "新增词");
        long wordId = created.data().path("id").asLong();
        assertThat(created.data().path("enabled").asBoolean()).isTrue();

        // 重复 → 40900
        assertCode(addSensitiveWord(word), 40900, 409, "重复加词");
        // 空词 → 40001
        assertCode(api.post("/api/v1/admin/community/sensitive-words",
                Map.of("word", "  "), adminToken()), 40001, 400, "空白词");

        // 停用：不删（留着它才能解释「昨天为什么拦了那条内容」）
        Map<String, Object> disable = new LinkedHashMap<>();
        disable.put("word", word);
        disable.put("enabled", false);
        ApiClient.ApiCall disabled = api.put("/api/v1/admin/community/sensitive-words/" + wordId,
                disable, adminToken());
        assertOk(disabled, "停用词");
        assertThat(disabled.data().path("enabled").asBoolean()).isFalse();
        assertThat(api.get("/api/v1/admin/community/sensitive-words?page_size=100", adminToken())
                .body().toString()).contains(word);

        // 只传停用标记时不改词本身（改个说明顺手把词改掉是最容易踩的一脚）
        assertOk(api.put("/api/v1/admin/community/sensitive-words/" + wordId,
                Map.of("word", word, "enabled", true), adminToken()), "启用词");
        assertOk(api.put("/api/v1/admin/community/sensitive-words/" + wordId,
                Map.of("word", word), adminToken()), "只改说明");
        // 改后的词与已有词重复 → 40900
        assertCode(api.put("/api/v1/admin/community/sensitive-words/" + wordId,
                Map.of("word", "加微信"), adminToken()), 40900, 409, "改成已有词");
        assertCode(api.put("/api/v1/admin/community/sensitive-words/999999",
                Map.of("word", "不存在的词条"), adminToken()), 40400, 404, "改不存在的词");

        // 去掉 enabled 字段再改：开关不动（仍是启用）
        ApiClient.ApiCall stillEnabled = api.put("/api/v1/admin/community/sensitive-words/" + wordId,
                Map.of("word", word, "remark", "改了说明"), adminToken());
        assertOk(stillEnabled, "改说明");
        assertThat(stillEnabled.data().path("enabled").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("审核通过 / 驳回都要有内容才谈得上：不存在的内容一律 40400")
    void missingContentIsNotFound() {
        assertCode(approveContent(1, 999_999L), 40400, 404, "通过不存在的卡片");
        assertCode(approveContent(2, 999_999L), 40400, 404, "通过不存在的提问");
        assertCode(approveContent(3, 999_999L), 40400, 404, "通过不存在的回答");
        assertCode(rejectContent(1, 999_999L, "理由"), 40400, 404, "驳回不存在的卡片");
    }
}
