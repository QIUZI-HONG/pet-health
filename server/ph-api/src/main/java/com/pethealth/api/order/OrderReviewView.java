package com.pethealth.api.order;

import java.time.LocalDateTime;

/**
 * 一条评价，对应 contract/app.yaml 的 {@code OrderReviewView}。同一个形状用在三处：
 * {@code POST /orders/{order_id}/review} 的响应、{@code GET /providers/{provider_id}/reviews}
 * 的列表行、以及订单详情的 {@code OrderView.review}。
 *
 * <p><b>没有评价人字段</b>（昵称 / 手机号 / 头像都不下发）：第一版不做匿名开关，也没想清楚
 * 「评价要不要带用户身份」——带了就要连带处理脱敏与展示授权，所以先不给。门店页上显示的是
 * 「N 分 + 一句话」，不显示是谁说的。{@code petId} 同理不下发（那是订单内部关系，
 * 界面上没有用处，少一个字段就少一处泄漏面）。
 *
 * <p>{@code content} 可以为空（只打分不写字）：界面要能把这种行显示成「只打了分」，
 * 而不是一个空白块。
 */
public record OrderReviewView(
        Long id,
        Long orderId,
        Long providerId,
        Integer rating,
        String content,
        LocalDateTime createdAt) {
}
