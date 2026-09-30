package com.pethealth.privilege.api;

import com.pethealth.privilege.domain.RightsGrant;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 权益引擎对外接口——**判定的唯一出口**（ADR-0038 第三节）。
 *
 * <p>各模块（AI 额度、完整报告、社区发帖）都读 {@link #evaluate}：
 * 「这个用户在每个码上是否生效、来自哪、到什么时候」，不各自查表拼规则。
 * 同一个用户在两处得到不同判定，是这类引擎最常见的坏味道，而它的表现是
 * 「用户明明有权益却被拦住」——用户无法自救。
 *
 * <p>判定是**实时**的（每次查库，不缓存）：权益决定功能能不能用，缓存漂移的代价不对称。
 * 三个来源的优先级 **订阅 &gt; 邀请 &gt; 打卡**，取生效记录里优先级最高的那一条，
 * **不比较到期时间**（「邀请得永久 + 订阅得月度」按到期比会把语义算错）。
 *
 * <p>实现在 {@code com.pethealth.privilege.service.RightsEngine}。
 */
public interface RightsApi {

    /**
     * 授予来源取值——**定义在 {@link RightsGrant}**（来源决定优先级与回收口径），
     * 这里给授予方（账号的注册基线权益、邀请结算、打卡）一套不依赖 {@code privilege.domain} 的名字。
     */
    int SOURCE_SUBSCRIPTION = RightsGrant.SOURCE_SUBSCRIPTION;
    int SOURCE_INVITE = RightsGrant.SOURCE_INVITE;
    int SOURCE_CHECK_IN = RightsGrant.SOURCE_CHECK_IN;
    int SOURCE_COMPENSATION = RightsGrant.SOURCE_COMPENSATION;

    /**
     * 一个用户的全部权益判定（**包含不生效的码**，带中文名）。
     *
     * @param userId 用户 id
     * @return 码 → 判定；同一个码只出现一次
     */
    Map<String, RightsState> evaluate(long userId);

    /** 单个码是否生效——给「只要一个布尔值」的调用方（如 AI 额度判定）。 */
    boolean isEffective(long userId, String code);

    /**
     * 授予一条权益。
     *
     * <p>幂等键是「用户 + 码 + 来源 + 来源引用」：批算重跑、事件重放只会有一条授予。
     * 与 {@link #isEffective} 不同，这里**不因已授权而报错**——重复授予是幂等的成功。
     *
     * @param command 授予命令
     * @throws com.pethealth.common.error.BusinessException 40400 权益码不存在；
     *         40001 到期时间早于当前（授一条立刻就过期的权益是调用方算错了）
     */
    void grant(GrantCommand command);

    /** 回收一条授予（运营动作或到期回收）。只回收这一条：订阅到期不触碰邀请得的永久权益。 */
    void revoke(long grantId);

    /**
     * 回收到期记录（批算调用），返回回收条数。幂等：重复跑第二次影响 0 行。
     */
    int revokeExpired();

    /**
     * 一条授予记录（跨模块用，不是契约 DTO）。
     *
     * @param source    1 订阅 / 2 邀请 / 3 打卡 / 4 运营补偿
     * @param sourceRef 来源引用（哪一次邀请 / 哪个账期），为空表示手动作业
     * @param expireAt  到期时间；为空表示**永久**
     */
    record GrantCommand(
            long userId,
            String code,
            int source,
            String sourceRef,
            LocalDateTime expireAt,
            String remark) {
    }

    /**
     * 一个码的判定结果。
     *
     * @param source    生效那一条的来源；不生效时为 null
     * @param expireAt  生效那一条的到期时间；为空表示永久
     * @param effective 当前是否生效
     */
    record RightsState(String code, boolean effective, Integer source, LocalDateTime expireAt) {
    }
}
