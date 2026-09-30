package com.pethealth.privilege.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 券实例，表 {@code coupon}——券池里的第三个对象：**发给某个用户的某一 张券**。
 *
 * <p>五条口径：
 *
 * <ul>
 *   <li><b>来源五种</b>：邀请 / 打卡任务 / 积分兑换 / 平台补贴 / 月度阶梯。
 *       前四种是 ADR-0037 定的；第五种（月度阶梯 F018）的触发路径与前四种都不同，
 *       硬塞进「积分兑换」会让对账看不清券从哪来（取舍见 ADR-0046）；
 *   <li><b>面额 / 门槛 / 适用范围都是发放时的快照</b>：券是平台对用户的承诺，
 *       模板随后改面额不能改写已经发出去的券；
 *   <li><b>「占用中」是算出来的</b>：{@code status in (1,2) 且 valid_until ≥ now}。
 *       为什么要带上时间而不只看那一列状态：过期由每日批算翻状态（{@code 4}），
 *       批算与读取之间有一个窗口，只看状态会让「已经过期还占着服务者额度」这种窗口存在；
 *   <li><b>核销是原子的条件更新</b>（ADR-0038 第二节）：靠 {@code UPDATE ... WHERE status IN (1,2)}
 *       的受影响行数判断，并发双击只有一个成功，重复核销给 80002；
 *   <li><b>不删已过期的券</b>：它参与对账（实例数 = 已核销 + 未过期未核销 + 已过期未核销）。
 * </ul>
 */
@TableName("coupon")
public class Coupon extends BaseEntity {

    public static final int SOURCE_INVITE = 1;
    public static final int SOURCE_CHECK_IN_TASK = 2;
    public static final int SOURCE_POINTS_EXCHANGE = 3;
    public static final int SOURCE_PLATFORM_SUBSIDY = 4;
    public static final int SOURCE_MONTHLY_LADDER = 5;

    public static final int STATUS_UNUSED = 1;
    /** 下单占用（ADR-0038 第一节：锁定 → 取消释放 → 核销时转已用）。 */
    public static final int STATUS_LOCKED = 2;
    public static final int STATUS_REDEEMED = 3;
    public static final int STATUS_EXPIRED = 4;

    private String code;
    private Long userId;
    private Long templateId;
    private Integer source;
    private String sourceRef;
    private Long contributionId;
    private Long providerId;
    private BigDecimal faceValue;
    private BigDecimal minAmount;
    private Integer scopeType;
    private String scopeCodes;
    private Integer status;
    private LocalDateTime validFrom;
    private LocalDateTime validUntil;
    private LocalDateTime issuedAt;
    private Long lockedOrderId;
    private LocalDateTime lockedAt;
    private Long redeemedOrderId;
    private LocalDateTime redeemedAt;
    private Long redeemedBy;

    /** 在给定时刻是否「占用中」（已发放、未核销、未过期）——额度公式里的那一项。 */
    public boolean isReservedAt(LocalDateTime now) {
        return (isStatus(STATUS_UNUSED) || isStatus(STATUS_LOCKED))
                && validUntil != null && !validUntil.isBefore(now);
    }

    /** 在给定时刻是否已过期（含批算已经把状态翻成「已过期」的）。 */
    public boolean isExpiredAt(LocalDateTime now) {
        return isStatus(STATUS_EXPIRED) || (validUntil != null && validUntil.isBefore(now));
    }

    private boolean isStatus(int expected) {
        return status != null && status == expected;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getTemplateId() {
        return templateId;
    }

    public void setTemplateId(Long templateId) {
        this.templateId = templateId;
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

    public Long getContributionId() {
        return contributionId;
    }

    public void setContributionId(Long contributionId) {
        this.contributionId = contributionId;
    }

    public Long getProviderId() {
        return providerId;
    }

    public void setProviderId(Long providerId) {
        this.providerId = providerId;
    }

    public BigDecimal getFaceValue() {
        return faceValue;
    }

    public void setFaceValue(BigDecimal faceValue) {
        this.faceValue = faceValue;
    }

    public BigDecimal getMinAmount() {
        return minAmount;
    }

    public void setMinAmount(BigDecimal minAmount) {
        this.minAmount = minAmount;
    }

    public Integer getScopeType() {
        return scopeType;
    }

    public void setScopeType(Integer scopeType) {
        this.scopeType = scopeType;
    }

    public String getScopeCodes() {
        return scopeCodes;
    }

    public void setScopeCodes(String scopeCodes) {
        this.scopeCodes = scopeCodes == null ? "" : scopeCodes;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getValidFrom() {
        return validFrom;
    }

    public void setValidFrom(LocalDateTime validFrom) {
        this.validFrom = validFrom;
    }

    public LocalDateTime getValidUntil() {
        return validUntil;
    }

    public void setValidUntil(LocalDateTime validUntil) {
        this.validUntil = validUntil;
    }

    public LocalDateTime getIssuedAt() {
        return issuedAt;
    }

    public void setIssuedAt(LocalDateTime issuedAt) {
        this.issuedAt = issuedAt;
    }

    public Long getLockedOrderId() {
        return lockedOrderId;
    }

    public void setLockedOrderId(Long lockedOrderId) {
        this.lockedOrderId = lockedOrderId;
    }

    public LocalDateTime getLockedAt() {
        return lockedAt;
    }

    public void setLockedAt(LocalDateTime lockedAt) {
        this.lockedAt = lockedAt;
    }

    public Long getRedeemedOrderId() {
        return redeemedOrderId;
    }

    public void setRedeemedOrderId(Long redeemedOrderId) {
        this.redeemedOrderId = redeemedOrderId;
    }

    public LocalDateTime getRedeemedAt() {
        return redeemedAt;
    }

    public void setRedeemedAt(LocalDateTime redeemedAt) {
        this.redeemedAt = redeemedAt;
    }

    public Long getRedeemedBy() {
        return redeemedBy;
    }

    public void setRedeemedBy(Long redeemedBy) {
        this.redeemedBy = redeemedBy;
    }
}
