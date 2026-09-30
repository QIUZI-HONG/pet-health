package com.pethealth.ai.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

import java.time.LocalDateTime;

/**
 * 转人工工单，表 {@code human_consult_request}（F006 的「转人工」出口）。
 *
 * <p>三态：0 待处理 → 1 已回复 / 2 已关闭。**终态不可回退**（见 {@code HumanConsultService}）：
 * 把已处理的单子退回待处理会让「谁在处理」失去意义。
 *
 * <p>口径与代价写在迁移 V37 的头部：**不接支付**（钱在门店付，ADR-0036）、
 * **不直接派给服务者**（派给谁需要匹配规则，运营先接住）、**不存用户自由文本与回复正文**
 * （前者多一处加密面，后者会与站内消息形成两个副本）。
 *
 * <p>`consultId` 上有唯一键：一次咨询只能转一次，**幂等键就是唯一键**（ADR-0052 第三节的纪律）。
 */
@TableName("human_consult_request")
public class HumanConsultTicket extends BaseEntity {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_REPLIED = 1;
    public static final int STATUS_CLOSED = 2;

    private Long userId;
    private Long petId;
    private Long consultId;
    private Integer riskLevel;
    private Integer status;
    private Long operatorId;
    private LocalDateTime handledAt;

    public boolean isHandled() {
        return status != null && (status == STATUS_REPLIED || status == STATUS_CLOSED);
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

    public Long getConsultId() {
        return consultId;
    }

    public void setConsultId(Long consultId) {
        this.consultId = consultId;
    }

    public Integer getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(Integer riskLevel) {
        this.riskLevel = riskLevel;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Long getOperatorId() {
        return operatorId;
    }

    public void setOperatorId(Long operatorId) {
        this.operatorId = operatorId;
    }

    public LocalDateTime getHandledAt() {
        return handledAt;
    }

    public void setHandledAt(LocalDateTime handledAt) {
        this.handledAt = handledAt;
    }
}
