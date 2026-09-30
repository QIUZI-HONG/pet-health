package com.pethealth.api.app;

import java.time.LocalDateTime;

/**
 * 一条转人工工单（C 端），对应 contract/app.yaml 的 {@code HumanConsultView}。
 *
 * <p>只给自己的那次咨询用，所以**没有用户与宠物的 id**（路径里已经有了），也没有运营的处置信息——
 * 少一个字段就少一处不该被看见的东西。
 *
 * <p>{@code status}：0 待处理 / 1 已回复 / 2 已关闭。重复提交返回的还是这条（幂等键是 `consultId`），
 * 所以用户连点两下看到的是同一张单子。
 */
public record HumanConsultView(
        Long id,
        Long consultId,
        Integer riskLevel,
        Integer status,
        LocalDateTime createdAt) {
}
