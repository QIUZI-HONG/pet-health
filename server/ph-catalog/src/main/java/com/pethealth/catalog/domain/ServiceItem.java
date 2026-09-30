package com.pethealth.catalog.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

import java.math.BigDecimal;

/**
 * 标准服务目录项（二级），表 {@code service_item}。
 *
 * <p>三条不变量，改代码前先读：
 *
 * <ul>
 *   <li><b>编码不可改、不可复用</b>（ADR-0034，项目所有者拍板）：{@code order_item.service_code}
 *       与券的适用范围都挂在它上面，所以「下架」只改 {@code status}，从不删行、从不换码。
 *   <li><b>价格区间属于平台</b>：{@code priceMin} / {@code priceMax} 是服务者定价的合法域，
 *       由运营维护（存库不写死代码）。
 *   <li><b>停用不牵连存量</b>：{@code status=0} 只挡住新的选品，已上架的服务项不自动下架。
 * </ul>
 */
@TableName("service_item")
public class ServiceItem extends BaseEntity {

    public static final int STATUS_ENABLED = 1;
    public static final int STATUS_DISABLED = 0;

    /** 平台自建（迁移里种入的初始目录）。 */
    public static final int SOURCE_PLATFORM = 1;
    /** 服务者提案审核通过后入库（F012 的目录外服务）。 */
    public static final int SOURCE_PROPOSAL = 2;

    private String code;
    private String categoryCode;
    private String name;
    private BigDecimal priceMin;
    private BigDecimal priceMax;
    private String priceUnit;
    private Integer durationMinutes;
    private Integer applicablePets;
    private String description;
    private Integer source;
    private Integer sortOrder;
    private Integer status;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getCategoryCode() {
        return categoryCode;
    }

    public void setCategoryCode(String categoryCode) {
        this.categoryCode = categoryCode;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BigDecimal getPriceMin() {
        return priceMin;
    }

    public void setPriceMin(BigDecimal priceMin) {
        this.priceMin = priceMin;
    }

    public BigDecimal getPriceMax() {
        return priceMax;
    }

    public void setPriceMax(BigDecimal priceMax) {
        this.priceMax = priceMax;
    }

    public String getPriceUnit() {
        return priceUnit;
    }

    public void setPriceUnit(String priceUnit) {
        this.priceUnit = priceUnit;
    }

    public Integer getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(Integer durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public Integer getApplicablePets() {
        return applicablePets;
    }

    public void setApplicablePets(Integer applicablePets) {
        this.applicablePets = applicablePets;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Integer getSource() {
        return source;
    }

    public void setSource(Integer source) {
        this.source = source;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public boolean isEnabled() {
        return status != null && status == STATUS_ENABLED;
    }
}
