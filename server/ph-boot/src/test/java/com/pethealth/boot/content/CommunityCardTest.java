package com.pethealth.boot.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.pethealth.boot.support.ApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 经验卡片：匿名、发文权益门禁、审核流转、点赞、聚合与分页（切片 #84 / F020）。
 *
 * <p>这一组用例里最要紧的两条是**否定性**的：卡片视图里不许出现任何身份字段、
 * 待审内容不许出现在公开列表里。它们没有「看起来对不对」这回事——只能靠断言把边界钉住，
 * 所以这里对字段名与列表内容都做了硬断言（而不是只断言「有返回」）。
 */
@DisplayName("社区 · 经验卡片（#84 / F020）")
class CommunityCardTest extends CommunityTestSupport {

    /** 卡片视图里**不许出现**的身份字段（对照 contract/app.yaml 的 CommunityCardView）。 */
    private static final Set<String> FORBIDDEN_CARD_FIELDS = Set.of(
            "author_id", "author_name", "user_id", "nickname", "pet_id", "pet_name", "source_ref",
            "machine_hits", "reviewed_by");

    // ---------------------------------------------------------------- 匿名性

    @Test
    @DisplayName("卡片恒匿名：作者、宠物、来源记录都不出现在任何调用方看到的视图里")
    void cardIsAlwaysAnonymous() {
        Actor author = postingActor();
        Actor reader = createActor();
        String petName = "旺财" + System.nanoTime();
        long petId = createPet(author, petName);
        long cardId = cardOk(author, petId, "我家猫的慢性肾病护理心得", "每天记录饮水量，三个月复查一次。");
        assertOk(approveContent(1, cardId), "运营通过");

        // 1) 别人读详情
        ApiClient.ApiCall byReader = api.get("/api/v1/app/community/cards/" + cardId, reader.token());
        assertOk(byReader, "他人读卡片详情");
        assertNoIdentityFields(byReader.data());
        assertThat(byReader.body().toString()).doesNotContain(petName);
        assertThat(byReader.data().path("mine").asBoolean()).isFalse();

        // 2) 列表里也一样（列表是匿名承诺最容易漏的地方）
        ApiClient.ApiCall list = api.get("/api/v1/app/community/cards?page=1&page_size=20", reader.token());
        assertOk(list, "他人读卡片列表");
        assertThat(list.data().path("total").asInt()).isEqualTo(1);
        JsonNode card = list.data().path("list").get(0);
        assertNoIdentityFields(card);
        assertThat(list.body().toString()).doesNotContain(petName);

        // 3) 作者自己看到的也是同一份匿名视图——「我的卡片」不额外透出身份
        ApiClient.ApiCall mine = api.get("/api/v1/app/community/cards?mine=true", author.token());
        assertOk(mine, "作者读我的卡片");
        JsonNode ownCard = mine.data().path("list").get(0);
        assertNoIdentityFields(ownCard);
        assertThat(ownCard.path("mine").asBoolean()).isTrue();

        // 4) 来源记录的关联确实落了库（匿名的是**对外视图**，不是数据本身）
        Map<String, Object> row = cardRow(cardId);
        assertThat(((Number) row.get("author_id")).longValue()).isEqualTo(author.userId());
        assertThat(((Number) row.get("pet_id")).longValue()).isEqualTo(petId);
        assertThat(row.get("source_ref")).isEqualTo("9001");
    }

    // ---------------------------------------------------------------- 权益门禁

    @Test
    @DisplayName("发文权益门禁：没有 community.post 一律 40300（与未登录的 40100 分开）")
    void postingRequiresCommunityPostRight() {
        Actor noRight = createActor();
        long petId = createPet(noRight, "旺财");

        ApiClient.ApiCall denied = createCard(noRight, cardBody(petId, "标题", "正文"));
        assertCode(denied, 40300, 403, "无权益发卡片");
        assertThat(denied.message()).contains("发帖权益");

        // 未登录是另一个码：两者混用会让前端把「有账号但没能力」引导去重新登录
        ApiClient.ApiCall anonymous = api.post("/api/v1/app/community/cards",
                cardBody(petId, "标题", "正文"));
        assertCode(anonymous, 40100, 401, "未登录发卡片");

        // 有权益就通
        grantPosting(noRight);
        assertOk(createCard(noRight, cardBody(petId, "标题", "正文")), "有权益发卡片");

        // 道数只拦发文：浏览与点赞不过这道门
        Actor reader = createActor();
        assertOk(api.get("/api/v1/app/community/cards", reader.token()), "无权益也能浏览");
    }

    @Test
    @DisplayName("来源记录归属：宠物不是自己的 → 40400（与「宠物不存在」同码）")
    void petMustBelongToAuthor() {
        Actor author = postingActor();
        Actor other = postingActor();
        long otherPet = createPet(other, "别人家的狗");

        ApiClient.ApiCall call = createCard(author, cardBody(otherPet, "标题", "正文"));
        assertCode(call, 40400, 404, "用别人的宠物发卡片");

        // 来源类型也只认 1 / 2
        Map<String, Object> badType = cardBody(createPet(author, "旺财"), "标题", "正文");
        badType.put("source_type", 9);
        assertCode(createCard(author, badType), 40001, 400, "来源类型非法");
    }

    // ---------------------------------------------------------------- 审核流转

    @Test
    @DisplayName("审核流转：待审内容不进公开列表，运营通过后才出现，下架后再次消失")
    void pendingContentIsInvisibleUntilApproved() {
        Actor author = postingActor();
        Actor reader = createActor();
        long petId = createPet(author, "旺财");
        long cardId = cardOk(author, petId, "术后护理记录", "术后前三天只喂流食。");

        // 1) 刚发出来是待审：公开列表与别人都看不到
        assertThat(((Number) cardRow(cardId).get("status")).intValue()).isZero();
        ApiClient.ApiCall publicList = api.get("/api/v1/app/community/cards", reader.token());
        assertOk(publicList, "公开列表");
        assertThat(publicList.data().path("total").asInt()).isZero();
        assertCode(api.get("/api/v1/app/community/cards/" + cardId, reader.token()),
                40400, 404, "他人读待审卡片");
        // 作者自己看得到（否则「发布了」在他眼里就是石沉大海）
        ApiClient.ApiCall mine = api.get("/api/v1/app/community/cards?mine=true", author.token());
        assertOk(mine, "作者读待审卡片");
        assertThat(mine.data().path("list").get(0).path("status").asInt()).isZero();
        assertThat(mine.data().path("list").get(0).path("status_name").asText()).isEqualTo("待审核");

        // 2) 运营通过 → 公开可见
        assertOk(approveContent(1, cardId), "运营通过");
        ApiClient.ApiCall afterApprove = api.get("/api/v1/app/community/cards", reader.token());
        assertThat(afterApprove.data().path("total").asInt()).isEqualTo(1);
        assertThat(afterApprove.data().path("list").get(0).path("status").asInt()).isEqualTo(1);

        // 3) 运营下架 → 又消失，作者能看到理由
        assertOk(rejectContent(1, cardId, "含未证实的疗效表述"), "运营下架");
        ApiClient.ApiCall afterReject = api.get("/api/v1/app/community/cards", reader.token());
        assertThat(afterReject.data().path("total").asInt()).isZero();
        assertCode(api.get("/api/v1/app/community/cards/" + cardId, reader.token()),
                40400, 404, "他人读已下架卡片");
        ApiClient.ApiCall authorView = api.get("/api/v1/app/community/cards?mine=true", author.token());
        JsonNode rejected = authorView.data().path("list").get(0);
        assertThat(rejected.path("status").asInt()).isEqualTo(2);
        assertThat(rejected.path("reject_reason").asText()).isEqualTo("含未证实的疗效表述");

        // 4) 非法迁移：已经是已发布 / 已驳回的再处置一次 → 40900（是状态冲突，不是参数错）
        assertOk(approveContent(1, cardId), "改判为通过");
        assertCode(approveContent(1, cardId), 40900, 409, "重复通过");
        assertOk(rejectContent(1, cardId, "再下架一次"), "下架");
        assertCode(rejectContent(1, cardId, "重复下架"), 40900, 409, "重复下架");

        // 5) 不存在的内容 → 40400
        assertCode(approveContent(1, 999_999L), 40400, 404, "通过不存在的内容");
    }

    @Test
    @DisplayName("机审：命中词表直接落已驳回；词表停用后同类内容不再被拦（改词即时生效）")
    void machineReviewBlocksOnSensitiveWords() {
        Actor author = postingActor();
        long petId = createPet(author, "旺财");
        String word = "测试违禁词" + System.nanoTime();
        ApiClient.ApiCall added = addSensitiveWord(word);
        assertOk(added, "加敏感词");

        // 命中：直接已驳回，不进待审队列，作者看得到（但不告诉他命中了哪个词）
        long cardId = cardOk(author, petId, "标题 " + word, "正文里也带上 " + word);
        assertThat(((Number) cardRow(cardId).get("status")).intValue()).isEqualTo(2);
        assertThat(String.valueOf(cardRow(cardId).get("machine_hits"))).contains(word);
        ApiClient.ApiCall mine = api.get("/api/v1/app/community/cards?mine=true", author.token());
        JsonNode blocked = mine.data().path("list").get(0);
        assertThat(blocked.path("reject_reason").asText()).isEqualTo("内容命中敏感词，未通过机审");
        // 命中细节只进运营端：说了词表就会被绕开（正文里的词是作者自己写的，那当然还在）
        assertThat(blocked.has("machine_hits")).isFalse();

        // 运营端看得到命中词（改判要依据）
        ApiClient.ApiCall queue = api.get(
                "/api/v1/admin/community/contents?content_type=1&status=2", adminToken());
        assertOk(queue, "审核队列");
        assertThat(queue.body().toString()).contains(word);
        // 运营可以改判：词表误伤是常态，改判的口子必须留着
        assertOk(approveContent(1, cardId), "改判为通过");

        // 停用这个词 → 新内容不再被拦（词表在库里，改完即时生效）
        ApiClient.ApiCall words = api.get("/api/v1/admin/community/sensitive-words?page_size=100", adminToken());
        assertOk(words, "查词表");
        long wordId = -1;
        for (JsonNode node : words.data().path("list")) {
            if (word.equals(node.path("word").asText())) {
                wordId = node.path("id").asLong();
            }
        }
        assertThat(wordId).as("刚加的词应当出现在词表里").isPositive();
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("word", word);
        update.put("enabled", false);
        assertOk(api.put("/api/v1/admin/community/sensitive-words/" + wordId, update, adminToken()), "停用词");

        long second = cardOk(author, petId, "标题 " + word, "正文里也带上 " + word);
        assertThat(((Number) cardRow(second).get("status")).intValue()).isZero();
    }

    // ---------------------------------------------------------------- 点赞

    @Test
    @DisplayName("点赞：幂等、计数不重复累加、取消点赞子资源口径幂等")
    void likeIsIdempotent() {
        Actor author = postingActor();
        Actor liker = createActor();
        long cardId = cardOk(author, createPet(author, "旺财"), "标题", "正文");
        assertOk(approveContent(1, cardId), "运营通过");

        String path = "/api/v1/app/community/cards/" + cardId + "/likes";
        ApiClient.ApiCall first = api.post(path, null, liker.token());
        assertOk(first, "第一次点赞");
        assertThat(first.data().path("like_count").asInt()).isEqualTo(1);
        assertThat(first.data().path("liked").asBoolean()).isTrue();

        ApiClient.ApiCall again = api.post(path, null, liker.token());
        assertOk(again, "重复点赞");
        assertThat(again.data().path("like_count").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `community_card_like` WHERE `card_id` = ?",
                Integer.class, cardId)).isEqualTo(1);

        // 取消 → 0；再取消一次也是成功（子资源删除幂等）
        ApiClient.ApiCall removed = api.delete(path, liker.token());
        assertOk(removed, "取消点赞");
        assertThat(removed.data().path("like_count").asInt()).isZero();
        assertOk(api.delete(path, liker.token()), "重复取消点赞");

        // 取消之后还能再点赞（把那一行翻回来，而不是插第二行）
        ApiClient.ApiCall relike = api.post(path, null, liker.token());
        assertOk(relike, "重新点赞");
        assertThat(relike.data().path("like_count").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `community_card_like` WHERE `card_id` = ?",
                Integer.class, cardId)).isEqualTo(1);

        // 待审 / 下架的卡片点不到
        long pending = cardOk(author, createPet(author, "旺财2"), "待审卡片", "正文");
        assertCode(api.post("/api/v1/app/community/cards/" + pending + "/likes", null, liker.token()),
                40400, 404, "给待审卡片点赞");
        assertCode(api.post("/api/v1/app/community/cards/999999/likes", null, liker.token()),
                40400, 404, "给不存在的卡片点赞");
    }

    // ---------------------------------------------------------------- 聚合与分页

    @Test
    @DisplayName("按同品种 / 同病聚合筛选，分页边界（页码、每页条数、越界翻页）")
    void filtersAndPaginationBoundaries() {
        Actor author = postingActor();
        Actor reader = createActor();
        long petId = createPet(author, "旺财");

        // 三张卡片：两张「柯基 + 慢性肾病」，一张「猫」
        List<Long> ids = new ArrayList<>();
        ids.add(cardWithTags(author, petId, 1, "柯基", "慢性肾病", "卡片一"));
        ids.add(cardWithTags(author, petId, 1, "柯基", "慢性肾病", "卡片二"));
        ids.add(cardWithTags(author, petId, 2, "布偶", null, "卡片三"));
        for (long id : ids) {
            assertOk(approveContent(1, id), "运营通过");
        }

        // 同品种：柯基两张
        ApiClient.ApiCall byBreed = api.get("/api/v1/app/community/cards?breed=柯基", reader.token());
        assertOk(byBreed, "按品种筛选");
        assertThat(byBreed.data().path("total").asInt()).isEqualTo(2);
        // 同病：慢性肾病两张
        assertThat(api.get("/api/v1/app/community/cards?disease_tag=慢性肾病", reader.token())
                .data().path("total").asInt()).isEqualTo(2);
        // 物种：猫一张
        assertThat(api.get("/api/v1/app/community/cards?species=2", reader.token())
                .data().path("total").asInt()).isEqualTo(1);
        // 物种 + 品种组合
        assertThat(api.get("/api/v1/app/community/cards?species=1&breed=柯基", reader.token())
                .data().path("total").asInt()).isEqualTo(2);

        // 分页：第 1 页 2 条（有下一页）、第 2 页 1 条（没有下一页）、越界页空且 has_more=false
        ApiClient.ApiCall page1 = api.get("/api/v1/app/community/cards?page=1&page_size=2", reader.token());
        assertOk(page1, "第一页");
        assertThat(page1.data().path("list")).hasSize(2);
        assertThat(page1.data().path("total").asInt()).isEqualTo(3);
        assertThat(page1.data().path("has_more").asBoolean()).isTrue();

        ApiClient.ApiCall page2 = api.get("/api/v1/app/community/cards?page=2&page_size=2", reader.token());
        assertThat(page2.data().path("list")).hasSize(1);
        assertThat(page2.data().path("has_more").asBoolean()).isFalse();

        ApiClient.ApiCall page9 = api.get("/api/v1/app/community/cards?page=9&page_size=2", reader.token());
        assertOk(page9, "越界页");
        assertThat(page9.data().path("list")).isEmpty();
        assertThat(page9.data().path("has_more").asBoolean()).isFalse();

        // 边界参数：页码从 1 起、每页 1–100
        assertCode(api.get("/api/v1/app/community/cards?page=0", reader.token()), 40001, 400, "页码 0");
        assertCode(api.get("/api/v1/app/community/cards?page_size=101", reader.token()), 40001, 400, "每页 101");
        assertCode(api.get("/api/v1/app/community/cards?species=3", reader.token()), 40001, 400, "物种 3");
        assertOk(api.get("/api/v1/app/community/cards?page_size=100", reader.token()), "每页 100");

        // 排序：时间倒序（id 倒序）——最新的卡片在第一页第一条
        assertThat(page1.data().path("list").get(0).path("id").asLong()).isEqualTo(ids.get(2));
    }

    @Test
    @DisplayName("带了品种就必须带物种（否则它不会被任何物种聚合命中）")
    void breedWithoutSpeciesIsRejected() {
        Actor author = postingActor();
        long petId = createPet(author, "旺财");
        Map<String, Object> body = cardBody(petId, "标题", "正文");
        body.put("breed", "柯基");
        assertCode(createCard(author, body), 40001, 400, "只带品种不带物种");

        body.put("species", 1);
        assertOk(createCard(author, body), "物种 + 品种");
    }

    // ---------------------------------------------------------------- 内部

    private long cardWithTags(Actor author, long petId, int species, String breed, String diseaseTag,
                              String title) {
        Map<String, Object> body = cardBody(petId, title, title + " 的正文");
        body.put("species", species);
        if (breed != null) {
            body.put("breed", breed);
        }
        if (diseaseTag != null) {
            body.put("disease_tag", diseaseTag);
        }
        ApiClient.ApiCall call = createCard(author, body);
        assertOk(call, "生成卡片：" + title);
        return call.data().path("id").asLong();
    }

    /** 卡片视图里**一个身份字段都不许有**（这一块的硬承诺，只能靠字段名断言钉住）。 */
    private static void assertNoIdentityFields(JsonNode card) {
        List<String> present = new ArrayList<>();
        card.fieldNames().forEachRemaining(present::add);
        for (String forbidden : FORBIDDEN_CARD_FIELDS) {
            assertThat(present)
                    .withFailMessage("卡片视图里出现了身份字段 `%s`（字段清单：%s）——卡片恒匿名是硬承诺", forbidden, present)
                    .doesNotContain(forbidden);
        }
    }
}
