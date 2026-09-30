package com.pethealth.content.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pethealth.api.content.ContentReviewItemView;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.time.AppTime;
import com.pethealth.common.trace.TraceIds;
import com.pethealth.content.domain.CommunityAnswer;
import com.pethealth.content.domain.CommunityCard;
import com.pethealth.content.domain.CommunityQuestion;
import com.pethealth.content.domain.ContentStatus;
import com.pethealth.content.domain.ContentType;
import com.pethealth.content.mapper.CommunityAnswerMapper;
import com.pethealth.content.mapper.CommunityCardMapper;
import com.pethealth.content.mapper.CommunityQuestionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 内容审核：队列 + 通过 + 驳回 / 下架（切片 #84 / F020；权限归运营，ADR-0037 第一节）。
 *
 * <p><b>状态机只有三条迁移</b>（{@code ContentStatus} 的类注释有图）：
 *
 * <pre>
 *   待审(0) --通过--&gt; 已发布(1)        待审(0) --驳回--&gt; 已驳回(2)
 *   已驳回(2) --通过--&gt; 已发布(1)（改判机审误伤）   已发布(1) --下架--&gt; 已驳回(2)
 * </pre>
 *
 * <p>「已经是那个状态」再处置一次 = **非法迁移 → 40900**（ADR-0038 第一节的口径），不是参数错误：
 * 这是状态冲突。两个运营同时点同一条内容时，第二个会得到 40900 而不是静默成功——
 * 这正是「谁的处置生效了」唯一能被看见的方式。
 *
 * <p><b>为什么读一次再条件更新</b>：读是为了给出人话的错误（「内容已经是已发布状态」），
 * 写才是闸门（源状态写进 WHERE）。只读不写的是提示，只有条件更新能保证并发下只有一个成功。
 *
 * <p><b>回答数联动</b>：一条回答被通过时给所属提问的 {@code answer_count} +1、
 * 从已发布被打回时 -1。这是**唯一**会改 {@code answer_count} 的地方——
 * 计数是冗余值，只有一处维护它才不会漂（与点赞计数同一口径）。
 */
@Service
public class ContentReviewService {

    private final CommunityCardMapper cardMapper;
    private final CommunityQuestionMapper questionMapper;
    private final CommunityAnswerMapper answerMapper;
    private final ContentViews views;

    public ContentReviewService(CommunityCardMapper cardMapper, CommunityQuestionMapper questionMapper,
                               CommunityAnswerMapper answerMapper, ContentViews views) {
        this.cardMapper = cardMapper;
        this.questionMapper = questionMapper;
        this.answerMapper = answerMapper;
        this.views = views;
    }

    // ---------------------------------------------------------------- 队列

    /**
     * 审核队列（按类型 + 状态 + 可选作者过滤，先到先审）。
     *
     * <p>三种内容各查一张表、各自分页：跨三张表 UNION 再分页在语义上没错，
     * 但它把「总条数」变成三次 COUNT 的和、把排序变成跨表归并，换来的只是省一次点击——
     * 而运营的界面本来就是按类型分页签的（契约里 {@code content_type} 必填）。
     */
    @Transactional(readOnly = true)
    public PageResult<ContentReviewItemView> queue(ContentType type, int status, Long authorId,
                                                   long page, long pageSize) {
        return switch (type) {
            case CARD -> PageResult.from(
                    cardMapper.selectPage(Page.of(page, pageSize),
                            Wrappers.<CommunityCard>lambdaQuery()
                                    .eq(CommunityCard::getStatus, status)
                                    .eq(authorId != null, CommunityCard::getAuthorId, authorId)
                                    .orderByAsc(CommunityCard::getId)),
                    views::reviewCard);
            case QUESTION -> PageResult.from(
                    questionMapper.selectPage(Page.of(page, pageSize),
                            Wrappers.<CommunityQuestion>lambdaQuery()
                                    .eq(CommunityQuestion::getStatus, status)
                                    .eq(authorId != null, CommunityQuestion::getAuthorId, authorId)
                                    .orderByAsc(CommunityQuestion::getId)),
                    views::reviewQuestion);
            case ANSWER -> PageResult.from(
                    answerMapper.selectPage(Page.of(page, pageSize),
                            Wrappers.<CommunityAnswer>lambdaQuery()
                                    .eq(CommunityAnswer::getStatus, status)
                                    .eq(authorId != null, CommunityAnswer::getAuthorId, authorId)
                                    .orderByAsc(CommunityAnswer::getId)),
                    views::reviewAnswer);
        };
    }

    // ---------------------------------------------------------------- 处置

    /** 通过：待审 / 已被机审驳回 → 已发布。 */
    @Transactional
    public ContentReviewItemView approve(long operatorId, ContentType type, long contentId) {
        LocalDateTime now = AppTime.now();
        String traceId = TraceIds.currentTraceId();
        switch (type) {
            case CARD -> {
                requirePendingOrRejected(cardOf(contentId).getStatus());
                requireTransition(cardMapper.approve(contentId, now, operatorId, traceId));
                return views.reviewCard(cardOf(contentId));
            }
            case QUESTION -> {
                requirePendingOrRejected(questionOf(contentId).getStatus());
                requireTransition(questionMapper.approve(contentId, now, operatorId, traceId));
                return views.reviewQuestion(questionOf(contentId));
            }
            case ANSWER -> {
                CommunityAnswer answer = answerOf(contentId);
                requirePendingOrRejected(answer.getStatus());
                requireTransition(answerMapper.approve(contentId, now, operatorId, traceId));
                // 只有「从非已发布翻到已发布」这一条路径会让回答数 +1
                questionMapper.increaseAnswerCount(answer.getQuestionId(), now, operatorId, traceId);
                return views.reviewAnswer(answerOf(contentId));
            }
        }
        throw new IllegalStateException("不可达：内容类型 " + type);
    }

    /** 驳回 / 下架：待审 → 已驳回、已发布 → 已下架（同一个状态迁移，对用户的效果一样）。 */
    @Transactional
    public ContentReviewItemView reject(long operatorId, ContentType type, long contentId, String reason) {
        LocalDateTime now = AppTime.now();
        String traceId = TraceIds.currentTraceId();
        switch (type) {
            case CARD -> {
                requirePendingOrPublished(cardOf(contentId).getStatus());
                requireTransition(cardMapper.reject(contentId, reason, now, operatorId, traceId));
                return views.reviewCard(cardOf(contentId));
            }
            case QUESTION -> {
                requirePendingOrPublished(questionOf(contentId).getStatus());
                requireTransition(questionMapper.reject(contentId, reason, now, operatorId, traceId));
                return views.reviewQuestion(questionOf(contentId));
            }
            case ANSWER -> {
                CommunityAnswer answer = answerOf(contentId);
                requirePendingOrPublished(answer.getStatus());
                boolean wasPublished = answer.getStatus() != null && answer.getStatus() == ContentStatus.PUBLISHED;
                requireTransition(answerMapper.reject(contentId, reason, now, operatorId, traceId));
                if (wasPublished) {
                    // 已发布的回答被下架：所属提问的回答数要跟着减，否则「3 个回答」点进去只有 2 个
                    questionMapper.decreaseAnswerCount(answer.getQuestionId(), now, operatorId, traceId);
                }
                return views.reviewAnswer(answerOf(contentId));
            }
        }
        throw new IllegalStateException("不可达：内容类型 " + type);
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 「已经是已发布状态了」的预检（通过动作的前提）。
     *
     * <p>只是给人话的错误文案；真正的闸门是条件更新（{@link #requireTransition}）。
     */
    private static void requirePendingOrRejected(Integer status) {
        if (status != null && status == ContentStatus.PUBLISHED) {
            throw BusinessException.conflict("内容已经是已发布状态，没有可处置的变化");
        }
    }

    /** 「已经是驳回状态了」的预检（驳回 / 下架动作的前提）。 */
    private static void requirePendingOrPublished(Integer status) {
        if (status != null && status == ContentStatus.REJECTED) {
            throw BusinessException.conflict("内容已经是驳回状态，没有可处置的变化");
        }
    }

    private static void requireTransition(int affectedRows) {
        if (affectedRows == 0) {
            // 预检之后才被另一个运营处置掉：条件更新是唯一的闸门，受影响行数为 0 就是没轮到我
            throw BusinessException.conflict("内容状态已被其他人处置，请刷新后再看");
        }
    }

    private CommunityCard cardOf(long id) {
        CommunityCard card = cardMapper.selectById(id);
        if (card == null) {
            throw BusinessException.notFound("内容不存在");
        }
        return card;
    }

    private CommunityQuestion questionOf(long id) {
        CommunityQuestion question = questionMapper.selectById(id);
        if (question == null) {
            throw BusinessException.notFound("内容不存在");
        }
        return question;
    }

    private CommunityAnswer answerOf(long id) {
        CommunityAnswer answer = answerMapper.selectById(id);
        if (answer == null) {
            throw BusinessException.notFound("内容不存在");
        }
        return answer;
    }
}
