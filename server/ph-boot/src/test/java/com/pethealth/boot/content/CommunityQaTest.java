package com.pethealth.boot.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.pethealth.boot.support.ApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 问答互助：提问 / 回答 / 采纳（切片 #84 / F020）。
 *
 * <p>三条被点名的边界都在这里：**采纳的越权**（只有提问者能采纳，别人一律 40400）、
 * **采纳的唯一性**（一条问题只能采纳一个回答，重复 40900）、
 * **待审内容不进阅读路径**（问答两侧都一样）。
 */
@DisplayName("社区 · 问答互助（#84 / F020）")
class CommunityQaTest extends CommunityTestSupport {

    private static final List<String> FORBIDDEN_FIELDS =
            List.of("author_id", "author_name", "user_id", "nickname", "pet_id", "pet_name");

    @Test
    @DisplayName("提问与回答都要发文权益（40300），回答只能给已发布的提问")
    void askingAndAnsweringNeedPostingRight() {
        Actor asker = postingActor();
        Actor noRight = createActor();
        long questionId = questionOk(asker, "慢性肾病该多久复查一次？", "猫 8 岁，肌酐偏高。");
        assertOk(approveContent(2, questionId), "运营通过提问");

        // 没有权益：提问、回答都拦，且是 40300 不是 40100
        assertCode(api.post("/api/v1/app/community/questions",
                        Map.of("title", "标题", "content", "正文"), noRight.token()),
                40300, 403, "无权益提问");
        assertCode(api.post("/api/v1/app/community/questions/" + questionId + "/answers",
                        Map.of("content", "我也想知道"), noRight.token()),
                40300, 403, "无权益回答");

        // 未过审的提问不能被回答（回答一条别人看不到的问题，回答本身也没有读者）
        long pending = questionOk(asker, "刚提的问题", "还没过审");
        assertCode(api.post("/api/v1/app/community/questions/" + pending + "/answers",
                        Map.of("content", "抢答"), asker.token()),
                40400, 404, "回答未过审的提问");
        assertCode(api.post("/api/v1/app/community/questions/999999/answers",
                        Map.of("content", "抢答"), asker.token()),
                40400, 404, "回答不存在的提问");
    }

    @Test
    @DisplayName("采纳：只有提问者、只能一个、且只能采纳已过审的回答")
    void adoptOnlyByAskerAndOnlyOnce() {
        Actor asker = postingActor();
        Actor answerer = postingActor();
        Actor outsider = createActor();
        long questionId = questionOk(asker, "术后饮食要注意什么？", "上周做了绝育手术。");
        assertOk(approveContent(2, questionId), "运营通过提问");
        long answerId = answerOk(answerer, questionId, "前三天流食，少量多餐。");
        assertOk(approveContent(3, answerId), "运营通过回答");

        String adoptPath = "/api/v1/app/community/questions/" + questionId
                + "/answers/" + answerId + "/adopt";

        // 1) 越权：别人（含回答者本人）采纳一律 40400（采纳按钮只出现在提问者界面上，
        //    别人来调只能是拿 id 试探——用 404 而不是 403 才不泄露「这个 id 存在」）
        assertCode(api.post(adoptPath, null, answerer.token()), 40400, 404, "回答者自己采纳");
        assertCode(api.post(adoptPath, null, outsider.token()), 40400, 404, "无关用户采纳");

        // 2) 回答不属于这条提问 → 40400
        long otherQuestion = questionOk(asker, "另一条问题", "正文");
        assertOk(approveContent(2, otherQuestion), "运营通过第二条提问");
        assertCode(api.post("/api/v1/app/community/questions/" + otherQuestion + "/answers/"
                        + answerId + "/adopt", null, asker.token()),
                40400, 404, "回答不属于这条提问");

        // 3) 没过的回答不能被采纳（提问者眼里它也不可见）
        long pendingAnswer = answerOk(answerer, questionId, "这条还没过审");
        assertCode(api.post("/api/v1/app/community/questions/" + questionId + "/answers/"
                        + pendingAnswer + "/adopt", null, asker.token()),
                40400, 404, "采纳未过审的回答");

        // 4) 提问者采纳：成功，且回答上的 adopted 是派生值
        ApiClient.ApiCall adopted = api.post(adoptPath, null, asker.token());
        assertOk(adopted, "提问者采纳");
        assertThat(adopted.data().path("adopted_answer_id").asLong()).isEqualTo(answerId);
        assertThat(adopted.data().path("mine").asBoolean()).isTrue();
        JsonNode adoptedAnswer = findAnswer(adopted.data().path("answers"), answerId);
        assertThat(adoptedAnswer.path("adopted").asBoolean()).isTrue();

        // 5) 唯一性：再采纳一次（同一个或其他回答）→ 40900
        assertCode(api.post(adoptPath, null, asker.token()), 40900, 409, "重复采纳同一个回答");
        long secondAnswer = answerOk(answerer, questionId, "另一条建议");
        assertOk(approveContent(3, secondAnswer), "运营通过第二条回答");
        assertCode(api.post("/api/v1/app/community/questions/" + questionId + "/answers/"
                        + secondAnswer + "/adopt", null, asker.token()),
                40900, 409, "改采纳另一个回答");
        // 真值只有一份：库里仍然是第一个
        assertThat(((Number) jdbc.queryForMap("SELECT `adopted_answer_id` FROM `community_question` "
                + "WHERE `id` = ?", questionId).get("adopted_answer_id")).longValue()).isEqualTo(answerId);
    }

    @Test
    @DisplayName("审核流转：待审的提问与回答都不进阅读路径，回答数只数已过审的")
    void reviewGatesBothSides() {
        Actor asker = postingActor();
        Actor answerer = postingActor();
        Actor reader = createActor();
        long questionId = questionOk(asker, "换粮后软便怎么办？", "换粮第三天开始软便。");

        // 提问待审：公开列表与详情都看不到
        assertThat(api.get("/api/v1/app/community/questions", reader.token())
                .data().path("total").asInt()).isZero();
        assertCode(api.get("/api/v1/app/community/questions/" + questionId, reader.token()),
                40400, 404, "他人读待审提问");
        // 作者自己看得到
        assertThat(api.get("/api/v1/app/community/questions?mine=true", asker.token())
                .data().path("list").get(0).path("status").asInt()).isZero();

        assertOk(approveContent(2, questionId), "运营通过提问");
        assertThat(api.get("/api/v1/app/community/questions", reader.token())
                .data().path("total").asInt()).isEqualTo(1);

        // 回答待审：别人看不到、提问者也看不到（没审过的内容不进入任何人的阅读路径），
        // 但回答者自己要看得到自己的那条（否则「我明明答了」与「详情里没有」会同时成立）
        long answerId = answerOk(answerer, questionId, "换成旧粮过渡一周试试。");
        JsonNode readerSees = api.get("/api/v1/app/community/questions/" + questionId, reader.token()).data();
        assertThat(readerSees.path("answers")).isEmpty();
        assertThat(readerSees.path("answer_count").asInt()).isZero();
        JsonNode answererView = api.get("/api/v1/app/community/questions/" + questionId, answerer.token()).data();
        assertThat(answererView.path("answers")).hasSize(1);
        assertThat(answererView.path("answers").get(0).path("status").asInt()).isZero();
        assertThat(answererView.path("answers").get(0).path("mine").asBoolean()).isTrue();

        // 通过 → 进入阅读路径，回答数 +1
        assertOk(approveContent(3, answerId), "运营通过回答");
        JsonNode afterApprove = api.get("/api/v1/app/community/questions/" + questionId, reader.token()).data();
        assertThat(afterApprove.path("answers")).hasSize(1);
        assertThat(afterApprove.path("answer_count").asInt()).isEqualTo(1);

        // 下架 → 又退出阅读路径，回答数跟着减（它数的是已过审的）
        assertOk(rejectContent(3, answerId, "建议里出现了处方药名"), "下架回答");
        JsonNode afterTakeDown = api.get("/api/v1/app/community/questions/" + questionId, reader.token()).data();
        assertThat(afterTakeDown.path("answers")).isEmpty();
        assertThat(afterTakeDown.path("answer_count").asInt()).isZero();

        // 提问被下架：详情对别人 40400，提问者自己能看到状态与理由
        assertOk(rejectContent(2, questionId, "描述里含未经证实的疗法"), "下架提问");
        assertCode(api.get("/api/v1/app/community/questions/" + questionId, reader.token()),
                40400, 404, "他人读已下架提问");
        JsonNode mine = api.get("/api/v1/app/community/questions?mine=true", asker.token())
                .data().path("list").get(0);
        assertThat(mine.path("status").asInt()).isEqualTo(2);
        assertThat(mine.path("reject_reason").asText()).isEqualTo("描述里含未经证实的疗法");
    }

    @Test
    @DisplayName("匿名与分页：问答视图里没有身份字段，列表分页边界正常")
    void anonymousAndPaginated() {
        Actor asker = postingActor();
        long questionId = questionOk(asker, "耳螨反复发作怎么办？", "用药两周又复发了。");
        assertOk(approveContent(2, questionId), "运营通过");

        JsonNode question = api.get("/api/v1/app/community/questions", asker.token())
                .data().path("list").get(0);
        List<String> fields = new ArrayList<>();
        question.fieldNames().forEachRemaining(fields::add);
        for (String forbidden : FORBIDDEN_FIELDS) {
            assertThat(fields).withFailMessage("提问视图里出现了身份字段 `%s`（%s）", forbidden, fields)
                    .doesNotContain(forbidden);
        }

        // 分页边界（与卡片同一套参数口径）
        assertCode(api.get("/api/v1/app/community/questions?page=0", asker.token()), 40001, 400, "页码 0");
        assertCode(api.get("/api/v1/app/community/questions?page_size=101", asker.token()),
                40001, 400, "每页 101");
        ApiClient.ApiCall far = api.get("/api/v1/app/community/questions?page=3&page_size=20", asker.token());
        assertOk(far, "越界页");
        assertThat(far.data().path("list")).isEmpty();
        assertThat(far.data().path("has_more").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("机审对回答同样生效：命中词表直接落已驳回，不进详情")
    void machineReviewAppliesToAnswers() {
        Actor asker = postingActor();
        Actor answerer = postingActor();
        String word = "测试回答敏感词" + System.nanoTime();
        assertOk(addSensitiveWord(word), "加敏感词");

        long questionId = questionOk(asker, "可以自己买药吗？", "不想去医院。");
        assertOk(approveContent(2, questionId), "运营通过提问");
        long answerId = answerOk(answerer, questionId, "去" + word + "买就行");
        assertThat(((Number) jdbc.queryForMap("SELECT `status` FROM `community_answer` WHERE `id` = ?",
                answerId).get("status")).intValue()).isEqualTo(2);
        assertThat(api.get("/api/v1/app/community/questions/" + questionId, asker.token())
                .data().path("answers")).isEmpty();
    }

    // ---------------------------------------------------------------- 内部

    private static JsonNode findAnswer(JsonNode answers, long answerId) {
        for (JsonNode answer : answers) {
            if (answer.path("id").asLong() == answerId) {
                return answer;
            }
        }
        throw new AssertionError("详情里没有 id=" + answerId + " 的回答：" + answers);
    }
}
