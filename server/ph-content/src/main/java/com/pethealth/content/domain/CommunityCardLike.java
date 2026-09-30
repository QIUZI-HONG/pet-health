package com.pethealth.content.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

/**
 * 点赞，表 {@code community_card_like}。
 *
 * <p>「同一个人对同一张卡片只算一次」这条不变量**就是**唯一键 {@code (card_id, user_id)}，
 * 不是应用层记的一个约定——并发双击点赞时，第二个请求会撞在唯一键上。
 *
 * <p>取消点赞是**软删除**（{@code is_deleted=1}，docs/conventions.md 的默认口径），
 * 于是「重新点赞」是**把那一行翻回来**而不是插一行：唯一键只约束一行一行，
 * 插一行会在第二次点赞时撞键。这也顺带保住了「一开始是什么时候点的」这个时间戳。
 */
@TableName("community_card_like")
public class CommunityCardLike extends BaseEntity {

    private Long cardId;
    private Long userId;

    public Long getCardId() {
        return cardId;
    }

    public void setCardId(Long cardId) {
        this.cardId = cardId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }
}
