package com.pethealth.content.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pethealth.api.content.CommunityAnswerCreateRequest;
import com.pethealth.api.content.CommunityAnswerView;
import com.pethealth.api.content.CommunityQuestionCreateRequest;
import com.pethealth.api.content.CommunityQuestionView;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.time.AppTime;
import com.pethealth.common.trace.TraceIds;
import com.pethealth.common.util.Text;
import com.pethealth.content.domain.CommunityAnswer;
import com.pethealth.content.domain.CommunityQuestion;
import com.pethealth.content.domain.ContentStatus;
import com.pethealth.content.mapper.CommunityAnswerMapper;
import com.pethealth.content.mapper.CommunityQuestionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 问答互助：提问 / 回答 / 采纳（切片 #84 / F020；决策见 ADR-0041 第一节）。
 *
 * <p><b>三条不变量</b>（交付文档 F020 与契约都点名了）：
 *
 * <ol>
 *   <li><b>回答也是发文</b>：提问与回答都过权益码 {@code community.post} 那道门
 *       （ADR-0041 第一节「发文权限走权益码」）；
 *   <li><b>采纳只有提问者</b>：判据是归属（{@code author_id == 当前用户}），
 *       不匹配一律 40400——采纳按钮只会出现在提问者界面上，别人来调只能是拿 id 试探；
 *   <li><b>一条问题只能采纳一个回答**：落点是条件更新
 *       （{@code WHERE adopted_answer_id IS NULL}），并发下只有一个能成，后到的拿 40900。
 *       采纳**不回退**：没有「取消采纳」这个动作（没有需求支撑，而它会让已解决的问题可以反复翻转）。
 * </ol>
 *
 * <p><b>审核</b>：提问与回答都先进待审；公开列表 / 详情只给已发布的内容。
 * 一条**没对别人可见的提问不能被回答**（40400）：回答一条别人看不到的问题，回答本身也没有读者。
 */
@Service
public class QuestionService {

    private static final Logger log = LoggerFactory.getLogger(QuestionService.class);

    private final CommunityQuestionMapper questionMapper;
    private final CommunityAnswerMapper answerMapper;
    private final PostingAccess postingAccess;
    private final SensitiveWordService sensitiveWords;
    private final ContentViews views;

    public QuestionService(CommunityQuestionMapper questionMapper, CommunityAnswerMapper answerMapper,
                           PostingAccess postingAccess, SensitiveWordService sensitiveWords,
                           ContentViews views) {
        this.questionMapper = questionMapper;
        this.answerMapper = answerMapper;
        this.postingAccess = postingAccess;
        this.sensitiveWords = sensitiveWords;
        this.views = views;
    }

    // ---------------------------------------------------------------- 提问

    /** 提问（需要 {@code community.post}）。 */
    @Transactional
    public CommunityQuestionView create(long userId, CommunityQuestionCreateRequest request) {
        postingAccess.requirePostingRight(userId);
        CommunityQuestion question = new CommunityQuestion();
        question.setAuthorId(userId);
        question.setTitle(request.title().trim());
        question.setContent(request.content().trim());
        question.setDiseaseTag(Text.trimToNull(request.diseaseTag()));
        question.setAnswerCount(0);
        applyMachineReview(question, request.title(), request.content());
        questionMapper.insert(question);
        log.debug("提问已创建：questionId={} userId={}", question.getId(), userId);
        return views.question(question, userId, List.of());
    }

    /** 问答列表：默认只看已发布；{@code mine=true} 看自己的（含待审与被拒）。**不带回答正文**。 */
    @Transactional(readOnly = true)
    public PageResult<CommunityQuestionView> list(long viewerId, String diseaseTag, boolean mine,
                                                 long page, long pageSize) {
        String diseaseFilter = Text.trimToNull(diseaseTag);
        Page<CommunityQuestion> result = questionMapper.selectPage(Page.of(page, pageSize),
                Wrappers.<CommunityQuestion>lambdaQuery()
                        .eq(!mine, CommunityQuestion::getStatus, ContentStatus.PUBLISHED)
                        .eq(mine, CommunityQuestion::getAuthorId, viewerId)
                        .eq(diseaseFilter != null, CommunityQuestion::getDiseaseTag, diseaseFilter)
                        .orderByDesc(CommunityQuestion::getId));
        List<CommunityQuestionView> viewsList = result.getRecords().stream()
                .map(question -> views.question(question, viewerId, List.of()))
                .toList();
        return PageResult.of(viewsList, result.getCurrent(), result.getSize(), result.getTotal());
    }

    /**
     * 提问详情（含回答）。
     *
     * <p>回答的可见范围是「**已发布的 + 我自己写的**」：别人待审的回答对提问者也不可见
     * （没审过的内容不进入任何人的阅读路径），而自己写的回答自己要能看到状态，
     * 否则「我明明答了」与「详情里没有」会同时成立。
     */
    @Transactional(readOnly = true)
    public CommunityQuestionView get(long viewerId, long questionId) {
        CommunityQuestion question = requireVisibleQuestion(viewerId, questionId);
        List<CommunityAnswer> answers = answerMapper.selectList(Wrappers.<CommunityAnswer>lambdaQuery()
                .eq(CommunityAnswer::getQuestionId, questionId)
                .and(w -> w.eq(CommunityAnswer::getStatus, ContentStatus.PUBLISHED)
                        .or().eq(CommunityAnswer::getAuthorId, viewerId))
                .orderByAsc(CommunityAnswer::getId));
        return views.question(question, viewerId,
                views.answers(answers, question.getAdoptedAnswerId(), viewerId));
    }

    // ---------------------------------------------------------------- 回答

    /** 回答（需要 {@code community.post}）；只能回答**已发布**的提问。 */
    @Transactional
    public CommunityAnswerView createAnswer(long userId, long questionId, CommunityAnswerCreateRequest request) {
        postingAccess.requirePostingRight(userId);
        CommunityQuestion question = questionMapper.selectById(questionId);
        if (question == null || !question.isPubliclyVisible()) {
            throw BusinessException.notFound("提问不存在或还没有对其他人可见");
        }
        CommunityAnswer answer = new CommunityAnswer();
        answer.setQuestionId(questionId);
        answer.setAuthorId(userId);
        answer.setContent(request.content().trim());
        String hits = sensitiveWords.hits(request.content());
        answer.setMachineHits(hits);
        answer.setStatus(hits == null ? ContentStatus.PENDING : ContentStatus.REJECTED);
        if (hits != null) {
            answer.setRejectReason("内容命中敏感词，未通过机审");
        }
        answerMapper.insert(answer);
        // **回答数不在这里 +1**：它数的是「已过审的回答」（契约里写明了），
        // 而新回答一律待审——计数在审核通过 / 下架时由 ContentReviewService 维护，
        // 只有一处维护才不会漂（与点赞计数同一口径）。
        log.debug("回答已创建：answerId={} questionId={} userId={}", answer.getId(), questionId, userId);
        return views.answer(answer, question.getAdoptedAnswerId(), userId);
    }

    // ---------------------------------------------------------------- 采纳

    /**
     * 采纳一个回答：只有提问者、且一条问题只能采纳一个。
     *
     * <p>三步判定，每一步的失败码都是刻意选的：
     *
     * <ol>
     *   <li>提问不存在 → 40400；**不是提问者 → 40400**（越权与不存在同码，不泄露 id 是否存在）；
     *   <li>回答不存在、不属于这条提问、或**还没过审** → 40400：没审过的回答对提问者也不可见，
     *       看不见的东西不能被采纳（否则等于给了「提议外人看不到的答案」这条路）；
     *   <li>已经采纳过 → **40900**：状态冲突，不是参数错（ADR-0038 第一节的口径）。
     * </ol>
     */
    @Transactional
    public CommunityQuestionView adopt(long askerId, long questionId, long answerId) {
        CommunityQuestion question = questionMapper.selectById(questionId);
        if (question == null || question.getAuthorId() == null || question.getAuthorId() != askerId) {
            throw BusinessException.notFound("提问不存在");
        }
        CommunityAnswer answer = answerMapper.selectById(answerId);
        if (answer == null || answer.getQuestionId() == null || answer.getQuestionId() != questionId
                || !answer.isPubliclyVisible()) {
            throw BusinessException.notFound("回答不存在或还没有对提问者可见");
        }
        java.time.LocalDateTime now = AppTime.now();
        int affected = questionMapper.adopt(questionId, askerId, answerId, now, TraceIds.currentTraceId());
        if (affected == 0) {
            // 条件更新没成：并发下另一个请求抢先采纳了（预检通过、写入落空的那一种）
            throw BusinessException.conflict("这条提问已经采纳过回答了");
        }
        log.debug("回答被采纳：questionId={} answerId={} askerId={}", questionId, answerId, askerId);
        return get(askerId, questionId);
    }

    // ---------------------------------------------------------------- 内部

    /** 机审 + 初始状态（与卡片同一口径：命中即驳回，未命中留待审）。 */
    private void applyMachineReview(CommunityQuestion question, String title, String content) {
        String hits = sensitiveWords.hits(title, content);
        question.setMachineHits(hits);
        question.setStatus(hits == null ? ContentStatus.PENDING : ContentStatus.REJECTED);
        if (hits != null) {
            question.setRejectReason("内容命中敏感词，未通过机审");
        }
    }

    private CommunityQuestion requireVisibleQuestion(long viewerId, long questionId) {
        CommunityQuestion question = questionMapper.selectById(questionId);
        if (question == null) {
            throw BusinessException.notFound("提问不存在或已删除");
        }
        if (!question.isPubliclyVisible() && (question.getAuthorId() == null || question.getAuthorId() != viewerId)) {
            throw BusinessException.notFound("提问不存在或已删除");
        }
        return question;
    }
}
