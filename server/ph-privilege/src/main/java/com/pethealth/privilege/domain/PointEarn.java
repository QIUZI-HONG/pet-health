package com.pethealth.privilege.domain;

/**
 * 「某人在一个时间窗内累计获得多少分」的聚合结果行（月度阶梯批算的输入）。
 *
 * <p>只算**正变动**：兑换消耗不该把累计拉下来——阶梯奖励看的是「这个人这个月攒了多少」，
 * 而不是月底还剩多少（否则先用分的人吃亏，而用分恰恰是平台鼓励的行为）。
 */
public class PointEarn {

    private Long userId;
    private Integer total;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Integer getTotal() {
        return total;
    }

    public void setTotal(Integer total) {
        this.total = total;
    }
}
