package com.pethealth.provider.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

/**
 * 服务者联盟分类维度，表 {@code provider_alliance_category}。
 *
 * <p>{@code id} 就是 {@link Provider#getCategory()} 里存的值——这张表是那个字段的**值域**，
 * 不是一个与它无关的字典。所以新增维度时运营拿到的是「可以分配给门店的一档」，
 * 而不是一条仅供展示的记录。
 *
 * <p>{@code enabled} 只影响「还能不能被新指定」，**不影响既有归属**：停用一档之后，
 * 已经挂在它上面的门店照旧显示这个名字（否则归约会凭空消失，而数据其实还在）。
 */
@TableName("provider_alliance_category")
public class AllianceCategory extends BaseEntity {

    public static final int DISABLED = 0;
    public static final int ENABLED = 1;

    private String code;
    private String name;
    private String description;
    private Integer sortOrder;
    private Integer enabled;

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

    public Integer getEnabled() {
        return enabled;
    }

    public void setEnabled(Integer enabled) {
        this.enabled = enabled;
    }
}
