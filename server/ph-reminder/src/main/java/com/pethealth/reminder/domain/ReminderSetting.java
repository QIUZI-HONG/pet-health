package com.pethealth.reminder.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

/** 用户对某类提醒的开关（默认全开；红色等级不允许置 0，见 ADR-0019）。 */
@TableName("reminder_setting")
public class ReminderSetting extends BaseEntity {

    private Long userId;
    private Integer type;
    private Integer enabled;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

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

    public boolean isOn() {
        return enabled == null || enabled == 1;
    }
}
