package com.pethealth.content.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

/**
 * 机审敏感词，表 {@code content_sensitive_word}。
 *
 * <p>这张表是**业务可调项**（ADR-0010 的第二层）：入库 + 运营后台维护，**不写死在代码里**——
 * 词表是随热点变的运营资产，改一次要发一次版，等于没人会改。
 *
 * <p>判定口径（实现见 {@code SensitiveWordService}）：**包含匹配 + 大小写不敏感**，不做正则、
 * 不做分词。运营要能肉眼预测「这句话为什么被拦」，这是词表能不能被信任的前提；
 * 而正则的威力换来的是「谁也说不清哪条会被拦」，那正是误伤的来源。
 *
 * <p>{@code enabled=false} 是**常规操作**而不是删除：误伤的词停掉即可，
 * 留着它才能解释「昨天为什么拦了那条内容」。
 */
@TableName("content_sensitive_word")
public class SensitiveWord extends BaseEntity {

    private String word;
    private String category;
    private Integer enabled;
    private String remark;

    public String getWord() {
        return word;
    }

    public void setWord(String word) {
        this.word = word;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Integer getEnabled() {
        return enabled;
    }

    public void setEnabled(Integer enabled) {
        this.enabled = enabled;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
