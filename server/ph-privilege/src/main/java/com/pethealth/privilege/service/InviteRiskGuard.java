package com.pethealth.privilege.service;

import com.pethealth.common.util.Text;
import com.pethealth.privilege.domain.InviteCode;
import com.pethealth.privilege.domain.InviteRelation;
import com.pethealth.privilege.domain.InviteRiskRecord;
import com.pethealth.privilege.mapper.InviteRiskRecordMapper;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 邀请反作弊的三层判据（ADR-0039 第一节），**判据是数据不是日志**。
 *
 * <p>三条在这里判（归因那一刻），第四条（24 小时无行为）由结算批算判：
 *
 * <table>
 *   <caption>判据</caption>
 *   <tr><th>规则</th><th>判据</th><th>已知的代价</th></tr>
 *   <tr><td>{@code SELF_INVITE}</td><td>被邀请人就是邀请人本人</td><td>无</td></tr>
 *   <tr><td>{@code SAME_DEVICE}</td><td>注册设备与该邀请码创建时的设备相同</td>
 *       <td>同一台电脑上的一家人会被误伤——这是<strong>刻意从严</strong>：误伤的用户可以找客服，
 *           而刷出来的券收不回来（ADR-0046 的待澄清）</td></tr>
 *   <tr><td>{@code SAME_IP_SEGMENT}</td><td>同一出口 IP <strong>且</strong>手机号前 7 位相同</td>
 *       <td>两个条件同时成立才拦：只看 IP 会误伤共用 WiFi 的同事、学校、咖啡店</td></tr>
 * </table>
 *
 * <p><b>IP 那一层只在「地址真的能区分来源」时才判</b>（{@link #carriesSourceIdentity}）：
 * 回环、私网、链路本地、CGNAT 这些地址是**被大量用户共享**的（同一个办公室、同一个运营商大内网），
 * 拿它们比「是不是同一个出口」得到的结论不成立——一旦拿它当判据，规则实际退化成「同号段就拦」，
 * 比 ADR-0046 想要的严得多。更要紧的是**部署形态**：正式环境前面有反向代理，而本仓的
 * {@code ClientIp} 刻意只读连接地址、不读可伪造的转发头（ADR-0028，可信代理白名单留到 #66）——
 * 那时代理之后的所有请求都是同一个地址，这一层会变成「永远成立」。
 * 等 #66 落地、能取到真实客户端 IP 之后，这条豁免可以收窄到只剩回环地址（今天先按地址段判）。
 *
 * <p>命中即写一条 {@code invite_risk_record}（带设备 / IP 原始判据，便于串出同一批账号），
 * 被拦下的邀请**不计数、不发奖**。**注册本身仍然成功**——不能因为邀请码可疑就不让用户注册。
 */
@Component
public class InviteRiskGuard {

    private final InviteRiskRecordMapper riskMapper;

    public InviteRiskGuard(InviteRiskRecordMapper riskMapper) {
        this.riskMapper = riskMapper;
    }

    /**
     * 归因时的三层判定。
     *
     * @param inviteCode 邀请码（码主人的设备 / IP / 号段在它上面）
     * @param inviteeUserId 被邀请人
     * @param deviceId 被邀请人注册设备，可为空（没有信号就不判这一条）
     * @param ip 被邀请人来源 IP，可为空
     * @param phoneSegment 被邀请人手机号前 7 位，可为空
     * @return 命中时返回判据代码（{@link InviteRelation#REJECT_SELF} 等），未命中返回空
     */
    public Optional<String> judge(InviteCode inviteCode, long inviteeUserId, String deviceId, String ip,
                                  String phoneSegment) {
        if (inviteCode.getUserId() != null && inviteCode.getUserId() == inviteeUserId) {
            record(InviteRelation.REJECT_SELF, inviteCode.getUserId(), inviteeUserId, deviceId, ip,
                    "被邀请人就是邀请人本人");
            return Optional.of(InviteRelation.REJECT_SELF);
        }
        if (notBlank(inviteCode.getOwnerDeviceId()) && notBlank(deviceId)
                && inviteCode.getOwnerDeviceId().equals(deviceId)) {
            record(InviteRelation.REJECT_SAME_DEVICE, inviteCode.getUserId(), inviteeUserId, deviceId, ip,
                    "注册设备与邀请码创建时的设备相同：" + deviceId);
            return Optional.of(InviteRelation.REJECT_SAME_DEVICE);
        }
        if (carriesSourceIdentity(ip)
                && notBlank(inviteCode.getOwnerIp()) && inviteCode.getOwnerIp().equals(ip)
                && notBlank(inviteCode.getOwnerPhoneSegment()) && notBlank(phoneSegment)
                && inviteCode.getOwnerPhoneSegment().equals(phoneSegment)) {
            record(InviteRelation.REJECT_SAME_IP_SEGMENT, inviteCode.getUserId(), inviteeUserId, deviceId, ip,
                    "同一出口 IP 且手机号同号段：" + ip + " / " + phoneSegment + "****");
            return Optional.of(InviteRelation.REJECT_SAME_IP_SEGMENT);
        }
        return Optional.empty();
    }

    /** 结算时的第四条：被邀请人 24 小时内无行为。 */
    public void recordNoActivity(long inviterUserId, long inviteeUserId) {
        record(InviteRelation.REJECT_NO_ACTIVITY, inviterUserId, inviteeUserId, null, null,
                "被邀请人在注册后 24 小时内没有行为");
    }

    private void record(String rule, Long inviterUserId, Long inviteeUserId, String deviceId, String ip,
                        String detail) {
        InviteRiskRecord record = new InviteRiskRecord();
        record.setRule(rule);
        record.setInviterUserId(inviterUserId);
        record.setInviteeUserId(inviteeUserId);
        record.setDeviceId(Text.trimToNull(deviceId));
        record.setIp(Text.trimToNull(ip));
        record.setDetail(detail);
        riskMapper.insert(record);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 这个地址能不能用来判断「是不是同一个来源」。
     *
     * <p>不能的那些是**由构造决定共享**的地址段：回环（本机 / 测试）、RFC1918 私网（办公室、家庭内网）、
     * 链路本地、CGNAT（运营商大内网，100.64/10）以及未取到地址时的空串。
     * 判据见 {@link InviteRiskGuard} 的类注释——它不是「放宽」，而是不让一个恒真的条件冒充判据。
     *
     * <p>IPv6 只排除回环（{@code ::1}）与唯一本地地址（{@code fc00::/7}）：公网 IPv6 通常按用户分配，
     * 比公网 IPv4 更能区分来源，不该被这条豁免一起放过。
     */
    private static boolean carriesSourceIdentity(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        String address = ip.trim();
        if (address.indexOf(':') >= 0) {
            String lower = address.toLowerCase(java.util.Locale.ROOT);
            return !lower.equals("::1") && !lower.startsWith("fc") && !lower.startsWith("fd")
                    && !lower.startsWith("fe80");
        }
        String[] parts = address.split("\\.");
        if (parts.length != 4) {
            return false; // 形状不认识就不判（同 SlotGrid 对脏数据的取舍：不猜）
        }
        int first;
        int second;
        try {
            first = Integer.parseInt(parts[0]);
            second = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return false;
        }
        if (first == 10 || first == 127 || first == 0) {
            return false;
        }
        if (first == 172 && second >= 16 && second <= 31) {
            return false;
        }
        if (first == 192 && (second == 168 || second == 0)) {
            return false;
        }
        if (first == 169 && second == 254) {
            return false;
        }
        return !(first == 100 && second >= 64 && second <= 127);
    }
}
