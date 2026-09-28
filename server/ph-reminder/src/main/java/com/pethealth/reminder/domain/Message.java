package com.pethealth.reminder.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

import java.time.LocalDateTime;

/**
 * 消息中心的一条内容（健康提醒 / 业务通知，见 ADR-0019）。
 *
 * <p>关键字段是 {@link #dedupKey}：它让生成逻辑天然幂等——同「宠物 + 类型 + 窗口」只一条，
 * 重复生成是**更新内容**（疫苗从「还有 7 天」到「还有 6 天」是同一条在变），
 * 而不是把消息中心刷成一堆重复项。
 */
@TableName("message")
public class Message extends BaseEntity {

    /** 消息大类。 */
    public static final int KIND_REMINDER = 1;
    public static final int KIND_NOTIFICATION = 2;

    /** 提醒类型（与 deliverable doc 的 type 对齐，另留业务通知的 7–10）。 */
    public static final int TYPE_VACCINE = 1;
    public static final int TYPE_DEWORM = 2;
    public static final int TYPE_DAILY = 3;
    public static final int TYPE_ABNORMAL = 4;
    public static final int TYPE_TREND = 5;
    public static final int TYPE_CHRONIC_ELDERLY = 6;
    public static final int TYPE_ORDER = 7;
    public static final int TYPE_COUPON = 8;
    public static final int TYPE_INVITE = 9;
    public static final int TYPE_SYSTEM = 10;

    /** 状态：只做站内，生成即「已发」；不保留「待发」态（没有外部通道要等）。 */
    public static final int STATUS_SENT = 1;
    public static final int STATUS_READ = 2;
    public static final int STATUS_CANCELLED = 3;

    /** 风险等级；红色提醒不可被用户关闭（ADR-0019）。 */
    public static final int RISK_NONE = 0;
    public static final int RISK_GREEN = 1;
    public static final int RISK_YELLOW = 2;
    public static final int RISK_RED = 3;

    private Long userId;
    private Long petId;
    private Integer kind;
    private Integer type;
    private String title;
    private String content;
    private Integer riskLevel;
    private LocalDateTime remindAt;
    private Integer status;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime readAt;
    private String dedupKey;
    private String actionHint;
    private String actionTarget;
    private String channelState;

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

    public Integer getKind() {
        return kind;
    }

    public void setKind(Integer kind) {
        this.kind = kind;
    }

    public Integer getType() {
        return type;
    }

    public void setType(Integer type) {
        this.type = type;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(Integer riskLevel) {
        this.riskLevel = riskLevel;
    }

    public LocalDateTime getRemindAt() {
        return remindAt;
    }

    public void setRemindAt(LocalDateTime remindAt) {
        this.remindAt = remindAt;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getReadAt() {
        return readAt;
    }

    public void setReadAt(LocalDateTime readAt) {
        this.readAt = readAt;
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public void setDedupKey(String dedupKey) {
        this.dedupKey = dedupKey;
    }

    public String getActionHint() {
        return actionHint;
    }

    public void setActionHint(String actionHint) {
        this.actionHint = actionHint;
    }

    public String getActionTarget() {
        return actionTarget;
    }

    public void setActionTarget(String actionTarget) {
        this.actionTarget = actionTarget;
    }

    public String getChannelState() {
        return channelState;
    }

    public void setChannelState(String channelState) {
        this.channelState = channelState;
    }

    public boolean isRead() {
        return status != null && status == STATUS_READ;
    }
}
