package com.pethealth.privilege.service;

import com.pethealth.privilege.domain.ContributionCouponStat;
import com.pethealth.privilege.domain.CouponContribution;

/**
 * 券额度的**唯一算法**：承诺额度与三个派生数。
 *
 * <p>公式（ADR-0037 第三节）：
 *
 * <pre>
 *   可发放 available = total_count − 已核销 − 占用中
 *   占用中 = 已发放、未核销、未过期（含下单锁定的）
 *   已过期 = 已发放、未核销、已过期   → 这部分**释放额度回池**
 *   对账   issued = redeemed + reserved + expired
 * </pre>
 *
 * <p>为什么把它收成一个类而不是散在几处：额度是「超发」与「发不出去」两种事故的共同判据。
 * 只要有一个地方抄歪了，就会出现「服务者明明还有额度却发不出去」或者更糟的「超发」。
 * 所有读写额度的路径（承诺 / 调额 / 撤回 / 发券 / 过期释放 / 统计展示）都走这里。
 *
 * <p>注意「过期释放」**不是一次写操作**：可发放是按「未过期未核销」实时算出来的，
 * 所以一张券一过期，额度自然就回来了（哪怕每日批算还没跑）。批算只做两件事：
 * 把状态翻成「已过期」、记一条额度流水——让状态与事实一致、让释放有据可查。
 */
public record CouponQuota(
        int totalCount,
        int issuedCount,
        int redeemedCount,
        int reservedCount,
        int expiredCount) {

    public static CouponQuota of(CouponContribution contribution, ContributionCouponStat stat) {
        int total = contribution.getTotalCount() == null ? 0 : contribution.getTotalCount();
        return new CouponQuota(total,
                intOf(stat == null ? null : stat.getIssued()),
                intOf(stat == null ? null : stat.getRedeemed()),
                intOf(stat == null ? null : stat.getReserved()),
                intOf(stat == null ? null : stat.getExpired()));
    }

    /** 还可发放多少张。**可能为负吗**：不会——调额与撤回都保证 total ≥ 已核销 + 占用中。 */
    public int available() {
        return totalCount - redeemedCount - reservedCount;
    }

    /** 已经发出去、还被约束住的部分（已核销 + 占用中）：调整额度时的下限。 */
    public int committed() {
        return redeemedCount + reservedCount;
    }

    /**
     * 完成率 = 已核销 ÷ 承诺额度（两位小数的字符串，给考核用）。
     *
     * <p>承诺额度为 0 时给 {@code "0.00"}：0 ÷ 0 没有意义，而这里不能返回 null——
     * 考核侧拿到 null 会当成「未计入」，与「一分没核销」是两件事。
     */
    public String completionRate() {
        if (totalCount <= 0) {
            return "0.00";
        }
        return java.math.BigDecimal.valueOf(redeemedCount)
                .divide(java.math.BigDecimal.valueOf(totalCount), 2, java.math.RoundingMode.HALF_UP)
                .toPlainString();
    }

    /** 对账是否平（issued = redeemed + reserved + expired）。不平说明有人绕过了这套算法。 */
    public boolean balanced() {
        return issuedCount == redeemedCount + reservedCount + expiredCount;
    }

    private static int intOf(Integer value) {
        return value == null ? 0 : value;
    }
}
