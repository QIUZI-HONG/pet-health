package com.pethealth.catalog.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

/**
 * 「症状 → 推荐目录项」的映射，表 {@code catalog_symptom_rule}（F011 规则版，ADR-0050 第二节）。
 *
 * <p>三条刻意的设计（理由与代价写在迁移 V36 的头部）：
 *
 * <ul>
 *   <li><b>症状词用知识侧的规范名</b>，不存口语变体——口语由知识侧的 alias 归一；
 *   <li><b>只映射到「项目」这一级</b>，不到门店：哪家店能做由 {@code provider_service} 回答，
 *       映射表掺和门店状态就要面对「这家今天不上架了」的同步问题；
 *   <li><b>只映射医疗类项目</b>（HE-*）：症状是靠身体信号推出来的，把它映到洗护/用品
 *       等于在疼痛上做推销。
 * </ul>
 */
@TableName("catalog_symptom_rule")
public class CatalogSymptomRule extends BaseEntity {

    public static final int STATUS_ENABLED = 1;
    public static final int STATUS_DISABLED = 0;

    private String symptomKeyword;
    private String itemCode;
    private Integer sortOrder;
    private Integer enabled;

    public String getSymptomKeyword() {
        return symptomKeyword;
    }

    public void setSymptomKeyword(String symptomKeyword) {
        this.symptomKeyword = symptomKeyword;
    }

    public String getItemCode() {
        return itemCode;
    }

    public void setItemCode(String itemCode) {
        this.itemCode = itemCode;
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
