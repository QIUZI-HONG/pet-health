package com.pethealth.content.service;

import com.pethealth.api.content.CommunityAnswerView;
import com.pethealth.api.content.CommunityCardView;
import com.pethealth.api.content.CommunityQuestionView;
import com.pethealth.api.content.ContentReviewItemView;
import com.pethealth.content.domain.CommunityAnswer;
import com.pethealth.content.domain.CommunityCard;
import com.pethealth.content.domain.CommunityQuestion;
import com.pethealth.content.domain.ContentStatus;
import com.pethealth.content.domain.ContentType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 内容实体 → 契约视图的装配（三种内容、两端视图都在这里，别处不要再拼一遍）。
 *
 * <p>三条集中在这里的口径：
 *
 * <ol>
 *   <li><b>C 端视图里没有作者</b>：{@code CommunityCardView} / {@code CommunityQuestionView} /
 *       {@code CommunityAnswerView} 都没有 author_id、昵称、宠物昵称——卡片恒匿名
 *       （ADR-0041 第一节）。这里**只出 {@code mine} 与 {@code liked}**：它们是
 *       「看的人自己」与内容的关系，不是作者的属性，不会泄露身份；
 *   <li><b>运营端视图里必须有作者</b>：审核是有处置权的动作，看不见对象就没法处置
 *       （ADR-0037 第一节把「内容审核」划给运营）。两种答案不矛盾——匿名承诺的对象是 C 端用户；
 *   <li><b>{@code adopted} 是派生值</b>：从提问的 {@code adoptedAnswerId} 比出来，
 *       回答行上没有这个字段（同一个事实存两处迟早会自相矛盾）。
 * </ol>
 */
@Component
public class ContentViews {

    // ---------------------------------------------------------------- C 端

    /** 单张卡片。 */
    public CommunityCardView card(CommunityCard card, long viewerId, boolean liked) {
        return new CommunityCardView(
                card.getId(),
                card.getSourceType(),
                CommunityCard.sourceTypeName(card.getSourceType()),
                card.getTitle(),
                card.getContent(),
                card.getSpecies(),
                card.getBreed(),
                card.getDiseaseTag(),
                card.getLikeCount() == null ? 0 : card.getLikeCount(),
                liked,
                isMine(card.getAuthorId(), viewerId),
                card.getStatus(),
                ContentStatus.name(card.getStatus()),
                card.getRejectReason(),
                card.getCreatedAt());
    }

    /** 卡片列表（{@code likedCardIds} 是**批量查出来的**：一页 20 张卡片不该有 20 次点赞查询）。 */
    public List<CommunityCardView> cards(List<CommunityCard> cards, long viewerId, Set<Long> likedCardIds) {
        List<CommunityCardView> views = new ArrayList<>(cards.size());
        for (CommunityCard card : cards) {
            views.add(card(card, viewerId, likedCardIds.contains(card.getId())));
        }
        return views;
    }

    /** 提问（{@code answers} 为空列表 = 列表场景：列表不下发回答正文，见契约说明）。 */
    public CommunityQuestionView question(CommunityQuestion question, long viewerId,
                                          List<CommunityAnswerView> answers) {
        return new CommunityQuestionView(
                question.getId(),
                question.getTitle(),
                question.getContent(),
                question.getDiseaseTag(),
                question.getAnswerCount() == null ? 0 : question.getAnswerCount(),
                question.getAdoptedAnswerId(),
                isMine(question.getAuthorId(), viewerId),
                question.getStatus(),
                ContentStatus.name(question.getStatus()),
                question.getRejectReason(),
                question.getCreatedAt(),
                answers == null ? List.of() : answers);
    }

    /** 回答（{@code adoptedAnswerId} 是**所属提问**的采纳值，不是回答自己的字段）。 */
    public CommunityAnswerView answer(CommunityAnswer answer, Long adoptedAnswerId, long viewerId) {
        return new CommunityAnswerView(
                answer.getId(),
                answer.getQuestionId(),
                answer.getContent(),
                adoptedAnswerId != null && adoptedAnswerId.equals(answer.getId()),
                isMine(answer.getAuthorId(), viewerId),
                answer.getStatus(),
                ContentStatus.name(answer.getStatus()),
                answer.getRejectReason(),
                answer.getCreatedAt());
    }

    public List<CommunityAnswerView> answers(List<CommunityAnswer> answers, Long adoptedAnswerId, long viewerId) {
        List<CommunityAnswerView> views = new ArrayList<>(answers.size());
        for (CommunityAnswer answer : answers) {
            views.add(answer(answer, adoptedAnswerId, viewerId));
        }
        return views;
    }

    // ---------------------------------------------------------------- 运营端

    public ContentReviewItemView reviewCard(CommunityCard card) {
        return new ContentReviewItemView(
                ContentType.CARD.code(), ContentType.CARD.label(), card.getId(), card.getAuthorId(),
                card.getTitle(), card.getContent(), null,
                card.getStatus(), ContentStatus.name(card.getStatus()),
                card.getRejectReason(), card.getMachineHits(), card.getCreatedAt());
    }

    public ContentReviewItemView reviewQuestion(CommunityQuestion question) {
        return new ContentReviewItemView(
                ContentType.QUESTION.code(), ContentType.QUESTION.label(), question.getId(), question.getAuthorId(),
                question.getTitle(), question.getContent(), null,
                question.getStatus(), ContentStatus.name(question.getStatus()),
                question.getRejectReason(), question.getMachineHits(), question.getCreatedAt());
    }

    /** 回答没有标题；{@code question_id} 让运营能跳到那条提问看上下文。 */
    public ContentReviewItemView reviewAnswer(CommunityAnswer answer) {
        return new ContentReviewItemView(
                ContentType.ANSWER.code(), ContentType.ANSWER.label(), answer.getId(), answer.getAuthorId(),
                null, answer.getContent(), answer.getQuestionId(),
                answer.getStatus(), ContentStatus.name(answer.getStatus()),
                answer.getRejectReason(), answer.getMachineHits(), answer.getCreatedAt());
    }

    private static boolean isMine(Long authorId, long viewerId) {
        return authorId != null && authorId == viewerId;
    }
}
