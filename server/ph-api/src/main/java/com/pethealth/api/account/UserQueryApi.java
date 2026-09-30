package com.pethealth.api.account;

import com.pethealth.api.app.UserProfile;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * 账号的**只读**查询接口（跨模块）：订单侧要显示「预约人是谁」，以及按手机号定位订单。
 *
 * <p>为什么需要它：服务者侧的订单列表 / 详情要给出预约人的**昵称与脱敏手机号**
 * （contract/provider.yaml 的 {@code ProviderOrderSummaryView.user_nickname} /
 * {@code user_phone}），核销还要支持「输入完整手机号搜索定位订单」——
 * 手机号在 {@code user} 表里是密文 + {@code phone_hash} 等值索引（ADR-0013），
 * 别的模块既不该、也没法自己算那两个值（加密与 HMAC 的密钥只归 ph-account）。
 *
 * <p><b>实现由 ph-account 提供（本次交付未接线，见 ADR-0048 的「需要协调」）</b>：
 * 两个方法都是 ph-account 已有的读（{@code UserMapper} 按 id 批量查、按 {@code phone_hash}
 * 等值查），不需要新写查询逻辑。在它落地之前，服务者侧的订单视图会**只显示得出订单本身的数据**，
 * 预约人一栏为空——而不是把手机号猜一个出来（猜错的那一栏比空着更坏）。
 *
 * <p><b>为什么复用 {@link UserProfile} 而不是新造内部 record</b>：见
 * {@link com.pethealth.api.provider.ProviderServiceQueryApi} 的说明——契约已冻结，
 * ph-api 里新造的 record 会被 {@code ContractDtoDrift} 判成「DTO 有、契约无」。
 */
public interface UserQueryApi {

    /**
     * 批量取用户资料。
     *
     * <p>{@code UserProfile.phone} 是**脱敏值**（{@code 138****8888}）：接口这一层就不出明文，
     * 服务者侧看到的也只有脱敏值（ADR-0013 / docs/conventions.md 的脱敏口径）。
     *
     * @param userIds 用户 id 集合；空集合不查库
     * @return id → 资料；**查不到的 id 不会出现在 Map 里**（账号注销后可能查不到，
     *         调用方不要假设每个入参都有值）
     */
    Map<Long, UserProfile> profiles(Collection<Long> userIds);

    /**
     * 按**完整手机号**等值查用户 id（核销的三个定位入口之一，ADR-0038 第二节）。
     *
     * <p>手机号在这里是入参、不是返回值：能查到那条订单，但看不到号码本身。
     * 号码的加密与哈希比对在 ph-account 内部完成，调用方不需要、也不该碰密钥。
     *
     * @param phone 完整手机号（11 位）；格式不对时返回空（调用方按「没找到」处理）
     * @return 命中的用户 id；没有该号码时为 {@link Optional#empty()}
     */
    Optional<Long> findUserIdByPhone(String phone);

    /**
     * 取某个账号手机号的**前 7 位**（号段），给邀请反作弊当判据用（ADR-0039 第一节）。
     *
     * <p>为什么调用方自己取不到：号码在 {@code user} 表里是密文（ADR-0013），解密与截取都在
     * ph-account 内部——别的模块既不该、也没法自己算。**只出前 7 位**，不出完整号码：
     * 号段的用途是「两个账号是不是同一批办的号」，判等即可，多给一位都是多余的暴露。
     *
     * <p>为什么这条判据必须由**服务端**取：邀请码主人那一侧原先由入参给，而唯一的调用方
     * 传的是 null——两层反作弊因此永远不会命中（2026-09-30 验收发现）。
     * 由本接口从库里读，客户端就参与不进来了。
     *
     * @param userId 账号 id
     * @return 前 7 位；账号不存在或号码形状不对时为 {@link Optional#empty()}（调用方按「没有信号」处理）
     */
    Optional<String> phoneSegmentOf(long userId);
}
