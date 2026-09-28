package com.pethealth.reminder.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

/**
 * 某类提醒的规则与阈值（业务可调项，必须入库——ADR-0010 与 #64 点名的缺口：
 * 交付文档把提醒阈值写死在了测试用例里）。
 *
 * <p>{@code config} 是 JSON，各类型自己的参数：疫苗/驱虫是 {@code advanceDays}，
 * 趋势是 {@code weightChangePercent} 与 {@code windowDays}，慢病/老年是 {@code intervalMonths}。
 * 解析失败按默认值处理并记日志——规则配错不该让提醒整体不工作。
 */
@TableName("reminder_rule")
public class ReminderRule extends BaseEntity {

    private Integer type;
    private Integer enabled;
    private String config;
    private String remark;

    public Integer getType() {
        return type;
    }

    public void setType(Integer type) {
        this.type = type;
    }

    public Integer getEnabled() {
        return enabled;
    }

    public void setEnabled(Integer enabled) {
        this.enabled = enabled;
    }

    public String getConfig() {
        return config;
    }

    public void setConfig(String config) {
        this.config = config;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public boolean isEnabled() {
        return enabled == null || enabled == 1;
    }
}
