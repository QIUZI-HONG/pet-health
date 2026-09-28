package com.pethealth.account.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

/**
 * 一份合规文档（表 {@code compliance_document}，迁移 V8）。
 *
 * <p>归在账号域：它与「用户的账号权利」是一件事的两面——隐私政策说明我们怎么用数据，
 * 注销与导出是用户行使权利的入口，两者改起来总是一起改。
 */
@TableName("compliance_document")
public class ComplianceDocument extends BaseEntity {

    private String code;
    private String title;
    private String body;
    private String version;
    private java.time.LocalDate effectiveFrom;
    private Integer isPlaceholder;
    private String remark;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public java.time.LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(java.time.LocalDate effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public Integer getIsPlaceholder() {
        return isPlaceholder;
    }

    public void setIsPlaceholder(Integer isPlaceholder) {
        this.isPlaceholder = isPlaceholder;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
