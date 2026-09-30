package com.pethealth.record.event;

import java.time.LocalDate;

/**
 * 打卡已提交（领域事件）。
 *
 * <p>与 {@link CheckInRecordedEvent} 的分工：那一条只在**有异常分项**时发（提醒模块关心的是
 * 「这只宠物今天不对劲」）；这一条**每次提交都发**，它表达的是「今天这一次打卡行为发生了」——
 * 打卡发分要盯的是这个事实，而不是异常与否。
 *
 * @param userId       打卡的人（发分按用户算：积分账是用户的，不是宠物的）
 * @param petId        打卡的宠物
 * @param businessDate 本次提交所属的**业务日**（东八区，提交那一刻的日期）。
 *                     注意它不是请求里那个 {@code date}：补录历史记录时两者会差几天，
 *                     而发分的幂等引用与「每日 1 次」都要按行为发生的业务日算
 */
public record CheckInSubmittedEvent(
        long userId,
        long petId,
        LocalDate businessDate) {
}
