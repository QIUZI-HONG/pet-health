package com.pethealth.api.order;

import java.time.LocalDate;

/**
 * 一个可预约的时段（号源），对应 contract/app.yaml 的 {@code AppointmentSlotView}。
 *
 * <p>号源的语义见 CONTEXT.md 的「号源」条目与 ADR-0038 第一节：**以时段为粒度**的容量单位，
 * 挂在服务项上。约满的时段**照样返回**（{@code full=true}）——让它从列表里消失，
 * 用户会以为门店那天根本不营业。
 *
 * <p>{@code bookedCount} 含「履约中」与「已完成」的订单：服务做完了也不释放号源
 * （那段营业时间已经被用掉了），只有取消才释放。
 *
 * <p>{@code capacity} 的来源：ADR-0038 只写了「按时段容量占用」，没写粒度与初始值由谁给，
 * 所以这里是实现侧定的最小形态——**按营业时间的整点小时切时段、每段默认容量 1**，
 * 容量值落在 {@code appointment_slot.capacity} 上（运营可按行调整），见 ADR-0048 的待澄清。
 */
public record AppointmentSlotView(
        LocalDate date,
        String startTime,
        String endTime,
        Integer capacity,
        Integer bookedCount,
        Integer availableCount,
        boolean full) {
}
