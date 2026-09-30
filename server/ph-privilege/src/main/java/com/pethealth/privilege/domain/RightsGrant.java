package com.pethealth.privilege.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

import java.time.LocalDateTime;

/**
 * 权益授予记录，表 {@code rights_grant}——**码 + 来源 + 到期**。
 *
 * <p>三条口径都在这个类上：
 *
 * <ul>
 *   <li><b>每条来源各写一条</b>：「邀请得的永久」与「订阅得的月度」是两条记录，
 *       不合并、不覆盖。合并之后「订阅到期要不要回收」就分不清收的是哪一份（ADR-0038 第三节）；
 *   <li><b>来源优先级 = {@code source} 的数值升序</b>（订阅 1 &gt; 邀请 2 &gt; 打卡 3 &gt; 补偿 4）：
 *       判定取生效记录里数值最小的那条，**不比较到期时间**；
 *   <li><b>到期只回收该来源那一条</b>（{@code status} 1→2）：订阅到期不触碰邀请得的永久权益。
 * </ul>
 */
@TableName("rights_grant")
public class RightsGrant extends BaseEntity {

    public static final int SOURCE_SUBSCRIPTION = 1;
    public static final int SOURCE_INVITE = 2;
    public static final int SOURCE_CHECK_IN = 3;
    /** 运营补偿（客诉处理）——不给运营这个口子，客诉只能靠改数据库解决。 */
    public static final int SOURCE_COMPENSATION = 4;

    public static final int STATUS_ACTIVE = 1;
    public static final int STATUS_REVOKED = 2;

    private Long userId;
    private String code;
    private Integer source;
    private String sourceRef;
    /** 为空表示永久（邀请来源就是这样）。 */
    private LocalDateTime expireAt;
    private Integer status;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String remark;
    private LocalDateTime revokedAt;

    /** 在给定时刻是否生效：未回收，且（永久 或 未到期）。 */
    public boolean isEffectiveAt(LocalDateTime now) {
        return status != null && status == STATUS_ACTIVE
                && (expireAt == null || expireAt.isAfter(now));
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public Integer getSource() {
        return source;
    }

    public void setSource(Integer source) {
        this.source = source;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public void setSourceRef(String sourceRef) {
        this.sourceRef = sourceRef;
    }

    public LocalDateTime getExpireAt() {
        return expireAt;
    }

    public void setExpireAt(LocalDateTime expireAt) {
        this.expireAt = expireAt;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public LocalDateTime getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(LocalDateTime revokedAt) {
        this.revokedAt = revokedAt;
    }
}
