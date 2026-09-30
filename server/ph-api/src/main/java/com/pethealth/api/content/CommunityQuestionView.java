package com.pethealth.api.content;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一条提问，对应 contract/app.yaml 的 {@code CommunityQuestionView}。
 *
 * <p>{@code answers} 只在**详情**里有内容，列表里是空数组：一屏十个问题带上全部回答，
 * 既没人看，也让分页失去意义。
 *
 * <p>{@code adoptedAnswerId} 是「这条问题被解决了没有」的**唯一真值**（库里在
 * {@code community_question.adopted_answer_id} 上，回答行不存副本）：一条问题只会有一个被采纳的回答，
 * 而采纳动作是条件更新（{@code WHERE adopted_answer_id IS NULL}）——并发下只有一个能成，
 * 后到的拿 40900。
 *
 * <p>{@code mine} 是「这条问题是不是我提的」：采纳只有一个入口（提问者），界面据此决定要不要显示它。
 *
 * @param id              提问 id
 * @param title           标题
 * @param content         正文
 * @param diseaseTag      慢病标签
 * @param answerCount     已过审的回答条数（待审与被拒的不算）
 * @param adoptedAnswerId 被采纳的回答 id；没采纳时为空
 * @param mine            当前调用者是不是提问者
 * @param status          审核状态：0 待审 / 1 已发布 / 2 已驳回或已下架
 * @param statusName      审核状态中文名
 * @param rejectReason    被驳回 / 下架的理由；**只有提问者自己的那份能读到它**（同卡片的口径）
 * @param createdAt       提问时间
 * @param answers         回答（仅详情接口下发生效内容）
 */
public record CommunityQuestionView(
        Long id,
        String title,
        String content,
        String diseaseTag,
        Integer answerCount,
        Long adoptedAnswerId,
        Boolean mine,
        Integer status,
        String statusName,
        String rejectReason,
        LocalDateTime createdAt,
        List<CommunityAnswerView> answers) {
}
