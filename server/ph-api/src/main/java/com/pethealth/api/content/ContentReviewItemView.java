package com.pethealth.api.content;

import java.time.LocalDateTime;

/**
 * 审核队列里的一行，对应 contract/admin.yaml 的 {@code ContentReviewItemView}。
 *
 * <p>三种社区内容（卡片 / 提问 / 回答）**共用这一个形状**：审核要的是「谁、什么时候、发了什么、
 * 现在什么状态」，四件事在三种内容上同构，各写一张视图只会让运营切换三个页面看同一件事。
 *
 * <p>{@code authorId} 只在这一端出现。这不是「匿名承诺的例外」——匿名承诺的对象是 C 端用户，
 * 而运营有处置权，也就必须看得见对象（ADR-0037 第一节把「内容审核」划给运营）。
 * 一条内容的可见性与「谁发的」这两件事，在 C 端与运营端本来就该有两种答案。
 *
 * @param contentType     内容类型：1 经验卡片 / 2 提问 / 3 回答
 * @param contentTypeName 内容类型中文名
 * @param contentId       内容 id
 * @param authorId        作者 id（**只在运营端出现**）
 * @param title           标题；回答没有标题，为空
 * @param content         正文**全文**（截断的审核等于没审）
 * @param questionId      回答所属的提问 id；卡片与提问为空
 * @param status          0 待审 / 1 已发布 / 2 已驳回或已下架
 * @param statusName      状态中文名
 * @param rejectReason    驳回 / 下架理由（运营填的原文）；没被驳回时为空
 * @param machineHits     机审命中的敏感词（逗号分隔）；未命中为空
 * @param createdAt       发布时间
 */
public record ContentReviewItemView(
        Integer contentType,
        String contentTypeName,
        Long contentId,
        Long authorId,
        String title,
        String content,
        Long questionId,
        Integer status,
        String statusName,
        String rejectReason,
        String machineHits,
        LocalDateTime createdAt) {
}
