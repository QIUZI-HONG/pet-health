package com.pethealth.api.order;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 评价晒单的提交，对应 contract/app.yaml 的 {@code OrderReviewRequest}
 * （{@code POST /api/v1/app/orders/{order_id}/review}）。
 *
 * <p><b>只有评分必填</b>：最低成本的一条反馈就是「几星」，文字是加码。要求必填会把
 * 「懒得写字」的用户劝退，而他们正是评价率的大头。所以 {@code content} 可空，
 * 空串在服务层按未填处理（存 null，不存出一个空字符串）。
 *
 * <p>评分是**整数 1–5**（不给半星），越界或缺失由校验注解拦在 40001。
 * 第一版没有图片 / 追评 / 匿名 / 维度拆分——这个形状里只有两个字段就是那件事本身。
 */
public record OrderReviewRequest(

        @NotNull(message = "请先选择评分（1–5 星）")
        @Min(value = 1, message = "评分最低 1 星")
        @Max(value = 5, message = "评分最高 5 星")
        Integer rating,

        @Size(max = 500, message = "评价最多 500 个字")
        String content) {
}
