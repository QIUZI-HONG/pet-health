package com.pethealth.privilege.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

/**
 * 积分任务清单，表 {@code point_task}——每日 / 每周两档，清单**入库存配置**（ADR-0038 第四节）。
 *
 * <p>任务引用一个已有的行为码，进度按**行为流水**聚合（当天 / 本周该行为有几条），
 * 所以任务不需要自己记进度，也不会出现「任务说做了、流水里没有」这种两份账。
 *
 * <p>**任务本身不额外发分**：{@code points} 只是把行为的分值取出来给前端展示。
 * 同一行为发两次奖励，正是 ADR-0038 第四节点名要钉死的那条。
 */
@TableName("point_task")
public class PointTask extends BaseEntity {

    public static final int PERIOD_DAILY = 1;
    public static final int PERIOD_WEEKLY = 2;

    public static final int STATUS_ENABLED = 1;
    public static final int STATUS_DISABLED = 0;

    private String code;
    private String name;
    private Integer period;
    private String behaviorCode;
    private Integer targetCount;
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

    public Integer getPeriod() {
        return period;
    }

    public void setPeriod(Integer period) {
        this.period = period;
    }

    public String getBehaviorCode() {
        return behaviorCode;
    }

    public void setBehaviorCode(String behaviorCode) {
        this.behaviorCode = behaviorCode;
    }

    public Integer getTargetCount() {
        return targetCount;
    }

    public void setTargetCount(Integer targetCount) {
        this.targetCount = targetCount;
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
