package com.pethealth.api.content;

import java.time.LocalDateTime;

/**
 * 经验卡片（**恒匿名**），对应 contract/app.yaml 的 {@code CommunityCardView}。
 *
 * <p>这个 record 里**没有**作者 id、昵称、宠物昵称，也没有来源记录的正文细节——
 * 这是刻意的，也是这一块唯一的硬承诺（ADR-0041 第一节、交付文档 F020
 * 「录入即生成卡片（匿名）」）。交付文档写的是「默认匿名」，实现收紧成**恒匿名**：
 * 没有非匿名的场景支撑，而留一个开关等于给这条隐私保证开一个口子（ADR-0051 的决定）。
 * **加字段前先想清楚：这个字段会不会让人拼回「这是谁家的猫」。**
 *
 * <p>{@code liked} / {@code mine} 是**看的人**与这张卡片的关系，不是作者的属性：
 * 它们不会泄露任何身份，却是界面必需的两笔信息（爱心是不是亮的、要不要显示「我的卡片」）。
 *
 * @param id             卡片 id
 * @param sourceType     来源记录类型：1 打卡 / 2 就医记录
 * @param sourceTypeName 来源类型中文名（打卡 / 就医记录）
 * @param title          标题
 * @param content        正文
 * @param species        物种快照：1 犬 / 2 猫；未带为空
 * @param breed          品种快照
 * @param diseaseTag     慢病标签快照（「同病」聚合依据）
 * @param likeCount      点赞数（同一个人只算一次）
 * @param liked          当前调用者点过赞没有
 * @param mine           当前调用者是不是作者
 * @param status         审核状态：0 待审 / 1 已发布 / 2 已驳回或已下架
 * @param statusName     审核状态中文名
 * @param rejectReason   被驳回 / 下架的理由；**只有作者自己的那份能读到它**（公开列表里恒为空）。
 *                       机审命中时是「内容命中敏感词」，运营处置时是运营填的原文——
 *                       **不告诉作者命中哪个词**：说了词表就会被绕开
 * @param createdAt      发布时间
 */
public record CommunityCardView(
        Long id,
        Integer sourceType,
        String sourceTypeName,
        String title,
        String content,
        Integer species,
        String breed,
        String diseaseTag,
        Integer likeCount,
        Boolean liked,
        Boolean mine,
        Integer status,
        String statusName,
        String rejectReason,
        LocalDateTime createdAt) {
}
