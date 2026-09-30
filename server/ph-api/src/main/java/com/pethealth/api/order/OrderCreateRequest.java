package com.pethealth.api.order;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 创建订单，对应 contract/app.yaml 的 {@code OrderCreateRequest}。
 *
 * <p>下单成功即做两件事：**占用一个号源**、{@code couponId} 非空时**锁定那张券**
 * （ADR-0038 第一节的「下单锁定 → 取消释放 → 核销时券转已用」）。
 *
 * <p>{@code serviceId} 是**服务者上架的服务项 id**（{@code provider_service.id}），不是目录项编码：
 * 价格、时长与是否在架都由它决定。服务项不属于该门店、或还没上架，一律按不存在处理（40400）——
 * 不区分「没有这项」和「不是他的」，免得用 id 探测别家门店的服务。
 *
 * <p>{@code appointmentDate} 与 {@code startTime} 一起定位一个号源：时间必须落在该门店当天的
 * 营业时段整点上、且还没过去（否则 40001），容量满了给 40900（ADR-0038 第一节的并发口径）。
 *
 * <p>没有任何收款字段：**钱在门店付**（ADR-0036），这张单只记展示用的预估实付。
 */
public record OrderCreateRequest(

        @NotNull(message = "宠物必填")
        Long petId,

        @NotNull(message = "服务者必填")
        Long providerId,

        @NotNull(message = "服务项必填")
        Long serviceId,

        @NotNull(message = "预约日期必填")
        LocalDate appointmentDate,

        @NotNull(message = "预约时段必填")
        @Size(max = 5, message = "时段格式应为 HH:mm")
        @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "预约时段格式应为 HH:mm")
        String startTime,

        /** 下单要用的券实例 id；不传表示不用券。券不可用给 80001，已核销给 80002。 */
        Long couponId,

        @Size(max = 255, message = "备注最长 255 个字符")
        String remark) {
}
