package com.pethealth.privilege.api;

import com.pethealth.privilege.domain.InviteRelation;
import java.time.LocalDateTime;

/**
 * 邀请的写侧对外接口——给**注册流程**与**建档流程**接线用。
 *
 * <p><b>本切片不接线</b>（注册与建档分别在 ph-account / ph-record，不在本次改动范围内），
 * 接线点如下（见 ADR-0046 的「需要协调」）：
 *
 * <ul>
 *   <li>{@link #attribute} —— 注册成功那一刻调一次（ph-account 的
 *       {@code AccountService.register}，拿到新用户 id 之后）。**归因只有这一次机会**：
 *       ADR-0039 明确不做事后补填，补填是刷券的入口；
 *   <li>{@link #markProfileCompleted} —— 被邀请人完成建档时调一次
 *       （「完善档案」在 ph-account 的档案接口，或 ph-record 的宠物建档，二选一由产品定）。
 *   <li>{@link #ensureInviteCode} —— C 端「我的邀请码」页首次进入时调一次（下一波 app.yaml）。
 * </ul>
 *
 * <p>实现在 {@code com.pethealth.privilege.service.InviteService}。
 */
public interface InviteAttributionApi {

    /**
     * 刚归因成功的关系状态：**待生效**——定义在 {@link InviteRelation#STATUS_PENDING}。
     * 展示侧要用它说「已绑定，等结算给结论」，而结算口径属于邀请模块内部。
     */
    int STATUS_ATTRIBUTED_PENDING = InviteRelation.STATUS_PENDING;

    /**
     * 取回或生成这个用户的邀请码（一人一码，重复调用返回同一个）。
     *
     * @param channel 生成时的入口：1 分享链接 / 2 注册表单手工填
     * @param deviceId 生成时的设备标识（反作弊判据之一，可为空）
     * @param ip 生成时的来源 IP（反作弊判据之一，可为空）
     * @param phoneSegment 码主人手机号**前 7 位**（同号段判据用；不存完整号码，可为空）
     */
    String ensureInviteCode(long userId, Integer channel, String deviceId, String ip, String phoneSegment);

    /**
     * 注册那一刻的归因。**只会调用一次**。
     *
     * <p>返回值里带 {@code attributed} 与 {@code reason}：被反作弊拦下时不是异常，
     * 而是一次「记录在案但不计数」（注册本身必须成功——不能因为邀请码可疑就不让用户注册）。
     *
     * @param command 邀请码 + 被邀请人 + 注册时的设备 / IP / 号段
     */
    Attribution attribute(AttributionCommand command);

    /**
     * 被邀请人完成建档。
     *
     * <p>它只**推进状态**（记下建档时刻、把关系推进到待结算），是否算「有效邀请」
     * 要等 24 小时观察窗结束由结算批算给结论（ADR-0039 的反作弊第三层：
     * 被邀请人 24 小时内无行为则不发券）。
     *
     * @return true 表示确实有这条待生效的邀请关系（没有则说明这个人不是被邀请来的）
     */
    boolean markProfileCompleted(long inviteeUserId);

    /**
     * 结算一批到期的关系（定时任务调用；也可被运营手动触发用于补偿）。
     *
     * @param limit 单批最多处理多少条（避免一次锁太多行）
     * @return 结算统计
     */
    SettlementResult settle(int limit);

    record AttributionCommand(
            String inviteCode,
            long inviteeUserId,
            Integer channel,
            String deviceId,
            String ip,
            String phoneSegment,
            LocalDateTime registeredAt) {
    }

    /**
     * 归因结果。
     *
     * @param attributed true 表示归因成功（关系落成待生效）；false 表示被拦下或码不可用
     * @param relationId 归因成功时的关系 id
     * @param reason     失败原因：{@code CODE_NOT_FOUND}（码不存在）/ {@code SELF_INVITE} /
     *                   {@code SAME_DEVICE} / {@code SAME_IP_SEGMENT} / {@code ALREADY_ATTRIBUTED}
     */
    record Attribution(boolean attributed, Long relationId, String reason) {

        public static Attribution ok(long relationId) {
            return new Attribution(true, relationId, null);
        }

        public static Attribution rejected(String reason) {
            return new Attribution(false, null, reason);
        }
    }

    record SettlementResult(int scanned, int effective, int invalid) {
    }
}
