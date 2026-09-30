package com.pethealth.order.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

/**
 * 一条订单评价，表 {@code order_review}（迁移 V40）。
 *
 * <p>三条领域事实写在字段上，别在服务层再各判一次：
 *
 * <ul>
 *   <li><b>一单一评</b>：{@code orderId} 上有唯一键。评价是订单的产物，「这次服务发生过几次」
 *       决定了「能评几次」——所以唯一键而不是「先查一下有没有」；
 *   <li><b>评分是整数 1–5</b>：不给半星（第一版）。越界由接口层拦（40001），
 *       领域这里只声明取值域；
 *   <li><b>文字可空</b>：只打分不写字是合法的评价，存 {@code null} 而不是空串——
 *       「没写」与「写了个空白」在展示上是同一件事，在数据上不该是两个值。
 * </ul>
 *
 * <p>没有评价人展示字段：{@code userId} 只用于「谁评的」这条数据事实（越权校验与将来的客服排查），
 * C 端门店页不显示评价人。
 */
@TableName("order_review")
public class OrderReview extends BaseEntity {

    /** 评分取值域（契约 {@code OrderReviewRequest.rating} 的 minimum / maximum）。 */
    public static final int MIN_RATING = 1;
    public static final int MAX_RATING = 5;

    /** 文字评价的字符上限（契约 {@code maxLength: 500}，与 DDL 的 VARCHAR(500) 一致）。 */
    public static final int MAX_CONTENT_LENGTH = 500;

    private Long orderId;
    private Long userId;
    private Long petId;
    private Long providerId;
    private Integer rating;
    private String content;

    /** 评分是否在取值域内。接口层已经拦过一道，这里是领域自己的最后一道。 */
    public static boolean isValidRating(Integer rating) {
        return rating != null && rating >= MIN_RATING && rating <= MAX_RATING;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getPetId() {
        return petId;
    }

    public void setPetId(Long petId) {
        this.petId = petId;
    }

    public Long getProviderId() {
        return providerId;
    }

    public void setProviderId(Long providerId) {
        this.providerId = providerId;
    }

    public Integer getRating() {
        return rating;
    }

    public void setRating(Integer rating) {
        this.rating = rating;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
