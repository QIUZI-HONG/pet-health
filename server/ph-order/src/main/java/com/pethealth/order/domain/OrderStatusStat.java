package com.pethealth.order.domain;

/**
 * 按状态分组的订单统计（**不是表**，是聚合查询的结果）。
 *
 * <p>只装事实：状态的条数与「预估实付」的合计。金额是**展示口径**——平台不经手资金
 * （ADR-0002 / ADR-0036），所以它是「门店应收多少」而不是「平台收了多少」。
 */
public class OrderStatusStat {

    private Integer status;
    private Long count;
    private java.math.BigDecimal payAmount;

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Long getCount() {
        return count;
    }

    public void setCount(Long count) {
        this.count = count;
    }

    public java.math.BigDecimal getPayAmount() {
        return payAmount;
    }

    public void setPayAmount(java.math.BigDecimal payAmount) {
        this.payAmount = payAmount;
    }
}
