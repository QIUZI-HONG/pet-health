package com.pethealth.privilege.domain;

/**
 * 券池按来源分组的一行统计（不是表，是聚合查询的结果行）。
 *
 * <p>四个数的口径必须和「额度怎么算」完全一致，否则总览与贡献详情会给出两个答案：
 *
 * <ul>
 *   <li>{@code issued}：已发放 = 全部券实例（含已核销与已过期）；
 *   <li>{@code redeemed}：已核销；
 *   <li>{@code reserved}：**未过期未核销**（{@code status in (1,2) 且 valid_until ≥ now}）；
 *   <li>{@code expired}：**已过期未核销**（{@code status = 4 或 valid_until &lt; now}）。
 * </ul>
 *
 * <p>四个条件互斥且覆盖全部行，所以恒有 {@code issued = redeemed + reserved + expired}——
 * 这就是 ADR-0037 的对账口径，不平说明有人绕过了这套读写路径。
 */
public class CouponStat {

    private Integer source;
    private Integer issued;
    private Integer redeemed;
    private Integer reserved;
    private Integer expired;

    public Integer getSource() {
        return source;
    }

    public void setSource(Integer source) {
        this.source = source;
    }

    public Integer getIssued() {
        return issued;
    }

    public void setIssued(Integer issued) {
        this.issued = issued;
    }

    public Integer getRedeemed() {
        return redeemed;
    }

    public void setRedeemed(Integer redeemed) {
        this.redeemed = redeemed;
    }

    public Integer getReserved() {
        return reserved;
    }

    public void setReserved(Integer reserved) {
        this.reserved = reserved;
    }

    public Integer getExpired() {
        return expired;
    }

    public void setExpired(Integer expired) {
        this.expired = expired;
    }
}
