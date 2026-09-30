package com.pethealth.privilege.api;

import com.pethealth.privilege.domain.Coupon;

import java.math.BigDecimal;
import java.util.List;

/**
 * 券的**写侧**对外接口：发券、下单前校验、锁定与释放、核销。
 *
 * <p>给谁用：
 *
 * <ul>
 *   <li>{@link #issue} —— 邀请阶梯、打卡任务、积分兑换、运营补贴四条发放路径都走它
 *       （ph-privilege 内部与 ph-order 都可能调）；
 *   <li>{@link #check} / {@link #lock} / {@link #release} / {@link #redeem} —— **订单侧**
 *       （ADR-0038 第一节的「下单锁定券 → 取消释放 → 核销时券转已用」与第二节的核销）。
 *       订单模块只调这四个方法，不碰 {@code coupon} 表（ADR-0006）。
 * </ul>
 *
 * <p>实现在 {@code com.pethealth.privilege.service.CouponService}。
 */
public interface CouponApi {

    /**
     * 来源码取值——**定义在 {@link Coupon#SOURCE_CHECK_IN_TASK}**，
     * 这里给发放方（记录模块的打卡得券）一套不依赖 {@code privilege.domain} 的名字。
     *
     * <p>为什么需要这层搬运：模块边界只放行对方的 {@code api} / {@code event} 包
     * （{@code ModuleBoundaryTest} 的判定），而来源码是「发放方要按名调用」的代码约定——
     * 与 {@code PointsApi.BEHAVIOR_*} 是同一条理由、同一种做法。
     */
    int SOURCE_CHECK_IN_TASK = Coupon.SOURCE_CHECK_IN_TASK;

    /**
     * 发一张券。
     *
     * <p>幂等：{@code (source, sourceRef)} 唯一。同一行为的同一引用重复调用**只发一张**，
     * 第二次返回首次那张（不报错也不重复发）——这是「同一行为不得重复发奖励」的落点，
     * 靠数据库唯一键兜底而不是靠调用方小心。
     *
     * @param command 发给谁、哪个模板、什么来源、来源引用
     * @return 券的信息（含券码）
     * @throws com.pethealth.common.error.BusinessException
     *         <ul>
     *           <li><b>40400</b>：模板不存在或已停用；
     *           <li><b>40900</b>：服务者成本券的额度已用完（message 里说明是哪家店、还剩多少）；
     *               平台补贴券超过发放上限同样走这个码。
     *         </ul>
     */
    CouponInfo issue(IssueCommand command);

    /**
     * 下单前校验这张券能不能用（不改变状态）。
     *
     * @param couponId    券 id
     * @param providerId  下单的服务者（核销门店）；平台补贴券可以不限店，但这里仍要传，
     *                    由适用范围决定是否放行
     * @param serviceCode 下单的目录项编码（按适用范围校验）
     * @param orderAmount 订单总额（按门槛校验）
     * @return 校验通过时的抵扣信息
     * @throws com.pethealth.common.error.BusinessException
     *         <ul>
     *           <li><b>40400</b>：券不存在或不属于该用户；
     *           <li><b>80001</b>（HTTP 200）：券不可用——已过期、已被别的订单占用、不满足门槛、
     *               不在适用范围内、或（服务者券）不是本店的券。message 是给用户看的一句中文；
     *           <li><b>80002</b>（HTTP 200）：券已核销。
     *         </ul>
     */
    CouponCheck check(long couponId, Long providerId, String serviceCode, BigDecimal orderAmount);

    /** 下单锁定（占用）：只有待使用的券能锁；重复锁同一订单是幂等的。 */
    void lock(long couponId, long orderId, long userId);

    /** 取消订单释放：只有被本订单锁定的券会被放回「待使用」。 */
    void release(long couponId, long orderId);

    /**
     * 核销：**原子的条件更新**（ADR-0038 第二节）。
     *
     * <p>并发双击只有一个成功，另一个拿 80002；已过期的券核销不了（80001）。
     * 核销 ≠ 收款：这一步只确认券被用了，钱在门店付（ADR-0036）。
     *
     * @param couponId 券 id
     * @param providerId 核销门店（服务者）
     * @param orderId 订单 id
     * @param operatorId 核销人（服务者后台账号 id），落进 {@code coupon.redeemed_by}
     * @throws com.pethealth.common.error.BusinessException 80001 / 80002
     */
    void redeem(long couponId, Long providerId, long orderId, long operatorId);

    /** 券的对外信息（跨模块用，不是契约 DTO）。 */
    record CouponInfo(
            long id,
            String code,
            long userId,
            long templateId,
            String templateName,
            BigDecimal faceValue,
            BigDecimal minAmount,
            int source,
            Long contributionId,
            Long providerId,
            Integer scopeType,
            List<String> scopeCodes) {
    }

    /** 校验通过时的抵扣信息。 */
    record CouponCheck(
            long couponId,
            String code,
            BigDecimal faceValue,
            BigDecimal minAmount,
            Long providerId) {
    }

    /**
     * 发券命令。
     *
     * @param source        来源（1 邀请 / 2 打卡任务 / 3 积分兑换 / 4 平台补贴 / 5 月度阶梯）
     * @param sourceRef     来源引用，与 source 一起构成幂等键；平台补贴券可以传空
     * @param contributionId 指定服务者贡献；传空表示由券池按规则挑一条（服务者成本券），
     *                       平台补贴券必须为空
     */
    record IssueCommand(
            long userId,
            long templateId,
            int source,
            String sourceRef,
            Long contributionId,
            String remark) {
    }
}
