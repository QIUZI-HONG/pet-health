package com.pethealth.privilege.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

/**
 * 权益码，表 {@code rights_code}——**一张码表**：系统里有哪些可授予的能力。
 *
 * <p>初始四码来自 ADR-0038 第三节（{@code ai.unlimited} / {@code report.full} /
 * {@code community.post} / {@code quota.ai.bonus}），运营可扩。
 *
 * <p>**扩码 ≠ 长出新能力**：这张表只定义「有这么一项能力」，判定逻辑仍在各模块的代码里。
 * 新增的码在判定侧默认不生效，直到有模块按它写判定——所以「加一行数据就多一项功能」
 * 这条期待必须在这里被否掉（ADR-0045 的决定）。
 *
 * <p>{@code care.mode} **不在码表里**：专项照护模式按医学事实自动开启（ADR-0032），
 * 挂成权益会出现「够条件但权益不足，于是看不到专项入口」的自相矛盾（ADR-0038）。
 */
@TableName("rights_code")
public class RightsCode extends BaseEntity {

    public static final int STATUS_ENABLED = 1;
    public static final int STATUS_DISABLED = 0;

    private String code;
    private String name;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String description;
    private Integer sortOrder;
    private Integer status;

    public boolean isEnabled() {
        return status != null && status == STATUS_ENABLED;
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
}
