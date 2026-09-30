package com.pethealth.privilege.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * 券模板，表 {@code coupon_template}——券池里的第一个对象：**一种券的定义**。
 *
 * <p>三个决定写在这里，因为它们决定别处怎么写：
 *
 * <ul>
 *   <li><b>只能由平台创建</b>（ADR-0037）：统一券池是这个机制的核心卖点，
 *       放开服务者自建等于没有券池。所以这个类只有运营侧的服务会写它；
 *   <li><b>成本归属只影响统计与考核，不产生资金</b>（ADR-0036）：
 *       {@code costBearer} 回答的是「这张券的抵扣算谁头上」，本项目钱在门店付，
 *       所以这里没有余额、结算、打款任何字段；
 *   <li><b>券实例存快照</b>：模板改面额不影响已发出的券，所以 {@code coupon} 表自己存了
 *       发放时的面额 / 门槛 / 适用范围（与 {@code provider_service} 不存目录快照正好相反——
 *       那边要的是「目录只有一个真相」，这边要的是「发出去的就是承诺」）。
 * </ul>
 */
@TableName("coupon_template")
public class CouponTemplate extends BaseEntity {

    /** 成本归服务者（服务者贡献的券只能用这一类模板）。 */
    public static final int COST_PROVIDER = 1;
    /** 成本归平台（运营创建、定向发放的补贴券）。 */
    public static final int COST_PLATFORM = 2;

    /** 不限适用范围。 */
    public static final int SCOPE_NONE = 0;
    /** 限服务分类（{@code scopeCodes} 放分类编码）。 */
    public static final int SCOPE_CATEGORY = 1;
    /** 限目录项（{@code scopeCodes} 放目录项编码）。 */
    public static final int SCOPE_ITEM = 2;

    public static final int STATUS_ENABLED = 1;
    public static final int STATUS_DISABLED = 0;

    private String code;
    private String name;
    private BigDecimal faceValue;
    private BigDecimal minAmount;
    private Integer validDays;
    private Integer costBearer;
    private Integer scopeType;
    private String scopeCodes;
    /** 发放上限（只对平台补贴券有意义）；为空表示不限。 */
    private Integer issueLimit;
    private Integer status;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String description;

    public boolean isEnabled() {
        return status != null && status == STATUS_ENABLED;
    }

    /** 是不是平台补贴券——服务者不能为它承诺额度，兑换也只兑它（ADR-0038 第四节）。 */
    public boolean isPlatformSubsidy() {
        return costBearer != null && costBearer == COST_PLATFORM;
    }

    /** 适用范围编码（CSV → 列表；空串给空列表，不给「含一个空字符串」的列表）。 */
    public List<String> scopeCodeList() {
        if (scopeCodes == null || scopeCodes.isBlank()) {
            return List.of();
        }
        return Arrays.stream(scopeCodes.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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

    public Integer getValidDays() {
        return validDays;
    }

    public void setValidDays(Integer validDays) {
        this.validDays = validDays;
    }

    public Integer getCostBearer() {
        return costBearer;
    }

    public void setCostBearer(Integer costBearer) {
        this.costBearer = costBearer;
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

    public Integer getIssueLimit() {
        return issueLimit;
    }

    public void setIssueLimit(Integer issueLimit) {
        this.issueLimit = issueLimit;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
