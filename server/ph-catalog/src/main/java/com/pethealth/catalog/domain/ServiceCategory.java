package com.pethealth.catalog.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

/**
 * 标准服务目录分类（一级），表 {@code service_category}（ADR-0034：两级结构，分类 → 项目）。
 *
 * <p>{@code code} 是业务主键：目录项的分类归属、券的适用范围都可能引它，
 * 所以**改分类只能改名与排序，不能改编码**（改码等于改引用）。
 *
 * <p>{@code itemCodePrefix} 是该项目编码的前两位（HE / GR / TR / BD / SP / IN）：
 * 编码不可改，所以前缀与分类绑定——运营新建分类时必须一次给对，之后不能再动。
 */
@TableName("service_category")
public class ServiceCategory extends BaseEntity {

    /** 启用：出现在服务者选品与 C 端浏览里。 */
    public static final int STATUS_ENABLED = 1;
    /** 停用：不再出现在选品与浏览里；已上架的服务项不受影响。 */
    public static final int STATUS_DISABLED = 0;

    private String code;
    private String itemCodePrefix;
    private String name;
    private String icon;
    private String description;
    private Integer sortOrder;
    private Integer status;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getItemCodePrefix() {
        return itemCodePrefix;
    }

    public void setItemCodePrefix(String itemCodePrefix) {
        this.itemCodePrefix = itemCodePrefix;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
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
