package com.pethealth.api.content;

import java.time.LocalDateTime;

/**
 * 一条回答，对应 contract/app.yaml 的 {@code CommunityAnswerView}。
 *
 * <p>**匿名**：没有作者 id、没有昵称，只有 {@code mine}（这条是不是我写的）。
 * {@code adopted} 是从提问表的 {@code adopted_answer_id} 派生的**展示值**——
 * 回答行上不存「我被采纳了没有」这个副本，同一个事实存两处迟早会自相矛盾。
 *
 * @param id         回答 id
 * @param questionId 所属提问 id
 * @param content    正文
 * @param adopted    是否被提问者采纳（同一条问题只会有一条 true）
 * @param mine       当前调用者是不是回答人
 * @param status     审核状态：0 待审 / 1 已发布 / 2 已驳回或已下架
 * @param statusName 审核状态中文名
 * @param rejectReason 被驳回 / 下架的理由；**只有回答人自己的那份能读到它**
 * @param createdAt  回答时间
 */
public record CommunityAnswerView(
        Long id,
        Long questionId,
        String content,
        Boolean adopted,
        Boolean mine,
        Integer status,
        String statusName,
        String rejectReason,
        LocalDateTime createdAt) {
}
