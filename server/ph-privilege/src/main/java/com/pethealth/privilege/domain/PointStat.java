package com.pethealth.privilege.domain;

/**
 * 积分总览的聚合结果行（不是表）。
 *
 * <p>{@code todayEarned} / {@code todayUsers} 是**业务日**口径（东八区），
 * 用来盯每日上限的实际命中情况——上限是被刷的重点，运营要看得见今天发了多少。
 */
public class PointStat {

    private Integer accounts;
    private Integer balanceTotal;
    private Integer earnedTotal;
    private Integer spentTotal;
    private Integer todayEarned;
    private Integer todayUsers;

    public Integer getAccounts() {
        return accounts;
    }

    public void setAccounts(Integer accounts) {
        this.accounts = accounts;
    }

    public Integer getBalanceTotal() {
        return balanceTotal;
    }

    public void setBalanceTotal(Integer balanceTotal) {
        this.balanceTotal = balanceTotal;
    }

    public Integer getEarnedTotal() {
        return earnedTotal;
    }

    public void setEarnedTotal(Integer earnedTotal) {
        this.earnedTotal = earnedTotal;
    }

    public Integer getSpentTotal() {
        return spentTotal;
    }

    public void setSpentTotal(Integer spentTotal) {
        this.spentTotal = spentTotal;
    }

    public Integer getTodayEarned() {
        return todayEarned;
    }

    public void setTodayEarned(Integer todayEarned) {
        this.todayEarned = todayEarned;
    }

    public Integer getTodayUsers() {
        return todayUsers;
    }

    public void setTodayUsers(Integer todayUsers) {
        this.todayUsers = todayUsers;
    }
}
