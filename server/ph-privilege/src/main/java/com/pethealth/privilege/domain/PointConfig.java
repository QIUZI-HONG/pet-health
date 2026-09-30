package com.pethealth.privilege.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

/**
 * 积分规则设置，表 {@code point_config}——**单行表**（id 恒为 1）。
 *
 * <p>为什么单独一张而不是代码常量：每日获取上限是**业务可调项**（ADR-0010 的三层配置），
 * 运营要在后台改得动（做活动、压刷量），改它不该发版。
 *
 * <p>默认 20 分（ADR-0038 第四节），**邀请与一次性项不占这个上限**——
 * 否则一个 20 分的邀请奖励会当天把上限吃掉，之后所有打卡都不发分了。
 */
@TableName("point_config")
public class PointConfig extends BaseEntity {

    /** 单行配置的固定主键。 */
    public static final long SINGLETON_ID = 1L;

    private Integer dailyEarnLimit;

    public Integer getDailyEarnLimit() {
        return dailyEarnLimit;
    }

    public void setDailyEarnLimit(Integer dailyEarnLimit) {
        this.dailyEarnLimit = dailyEarnLimit;
    }
}
