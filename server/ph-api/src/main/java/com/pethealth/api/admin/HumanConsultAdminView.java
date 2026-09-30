package com.pethealth.api.admin;

import java.time.LocalDateTime;

/**
 * 运营看到的转人工工单，对应 contract/admin.yaml 的 {@code HumanConsultAdminView}。
 *
 * <p>比 C 端多的是**排班与追溯要用的字段**：提交人、宠物、风险等级（红色优先看）、处置人与处置时间。
 * 手机号之类的身份信息**不在这里**：需要联系用户时按 `userId` 到用户管理里查——
 * 那条链路有它自己的脱敏与审计口径，在这一页复制一份等于多一处泄漏面。
 */
public record HumanConsultAdminView(
        Long id,
        Long userId,
        Long petId,
        Long consultId,
        Integer riskLevel,
        Integer status,
        Long operatorId,
        LocalDateTime handledAt,
        LocalDateTime createdAt) {
}
