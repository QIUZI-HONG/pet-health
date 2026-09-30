package com.pethealth.privilege.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

/**
 * 积分账户，表 {@code user_point}——一人一行：余额 + 两个累计值。
 *
 * <p>**积分是整数**，不参与任何金额计算：它与券是两套账，只能兑换券，不能兑换现金或提现
 * （CONTEXT.md 的积分条目）。
 *
 * <p>余额与流水必须在同一个事务里改，并给流水留下 {@code balance_after}——
 * 只记变动数的话，事后永远无法从流水重建当时的余额，客诉时对不上账。
 */
@TableName("user_point")
public class UserPoint extends BaseEntity {

    private Long userId;
    private Integer balance;
    private Integer totalEarned;
    private Integer totalSpent;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Integer getBalance() {
        return balance;
    }

    public void setBalance(Integer balance) {
        this.balance = balance;
    }

    public Integer getTotalEarned() {
        return totalEarned;
    }

    public void setTotalEarned(Integer totalEarned) {
        this.totalEarned = totalEarned;
    }

    public Integer getTotalSpent() {
        return totalSpent;
    }

    public void setTotalSpent(Integer totalSpent) {
        this.totalSpent = totalSpent;
    }
}
