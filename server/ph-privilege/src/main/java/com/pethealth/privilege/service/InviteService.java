package com.pethealth.privilege.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.time.AppTime;
import com.pethealth.common.util.Text;
import com.pethealth.privilege.api.CouponApi;
import com.pethealth.privilege.api.InviteAttributionApi;
import com.pethealth.privilege.api.PointsApi;
import com.pethealth.privilege.api.RightsApi;
import com.pethealth.privilege.domain.Coupon;
import com.pethealth.privilege.domain.InviteCode;
import com.pethealth.privilege.domain.InviteLadderAchievement;
import com.pethealth.privilege.domain.InviteLadderTier;
import com.pethealth.privilege.domain.InviteRelation;
import com.pethealth.privilege.domain.PointBehavior;
import com.pethealth.privilege.domain.RightsGrant;
import com.pethealth.privilege.mapper.InviteCodeMapper;
import com.pethealth.privilege.mapper.InviteLadderAchievementMapper;
import com.pethealth.privilege.mapper.InviteLadderTierMapper;
import com.pethealth.privilege.mapper.InviteRelationMapper;
import com.pethealth.privilege.mapper.PointRecordMapper;
import com.pethealth.api.reminder.BusinessMessageApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 邀请：邀请码、归因、有效邀请结算与阶梯奖励（切片 #111），决策见 ADR-0039 第一节与 ADR-0046。
 *
 * <p><b>归因只在注册那一刻</b>（{@link #attribute}）：链接带 {@code ?invite=CODE} 只做预填，
 * 以用户填的码为准，**不做事后补填**——补填是刷券的入口。{@code invitee_user_id} 唯一，
 * 一个被邀请人一辈子只被归因一次。
 *
 * <p><b>有效邀请 = 被邀请人完成建档 + 24 小时内有行为</b>：所以关系有「待生效」这个中间态，
 * 由 {@link #settle} 在观察窗结束后给结论。阶梯计数与积分都按**有效**数算，不按注册数——
 * 否则同一批刷出来的号当晚就能把阶梯奖领走。
 *
 * <p><b>奖励物不重复</b>：有效邀请给积分（20 分）；阶梯达标只在**达到门槛那一次**发奖励
 * （{@code (user_id, threshold)} 唯一）。两套账各自推进，但不会对同一个行为发两次同样的东西。
 */
@Service
public class InviteService implements InviteAttributionApi {

    private static final Logger log = LoggerFactory.getLogger(InviteService.class);

    /** 邀请码字符集：去掉容易看错的 0/O/1/I/L，码是给人念、给人抄的。 */
    private static final char[] CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();
    private static final int CODE_LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final InviteCodeMapper codeMapper;
    private final InviteRelationMapper relationMapper;
    private final InviteLadderTierMapper tierMapper;
    private final InviteLadderAchievementMapper achievementMapper;
    private final PointRecordMapper pointRecordMapper;
    private final InviteRiskGuard riskGuard;
    private final PointsApi pointsApi;
    private final CouponApi couponApi;
    private final RightsApi rightsApi;
    private final BusinessMessageApi businessMessages;

    public InviteService(InviteCodeMapper codeMapper, InviteRelationMapper relationMapper,
                         InviteLadderTierMapper tierMapper,
                         InviteLadderAchievementMapper achievementMapper,
                         PointRecordMapper pointRecordMapper, InviteRiskGuard riskGuard,
                         PointsApi pointsApi, CouponApi couponApi, RightsApi rightsApi,
                         BusinessMessageApi businessMessages) {
        this.codeMapper = codeMapper;
        this.relationMapper = relationMapper;
        this.tierMapper = tierMapper;
        this.achievementMapper = achievementMapper;
        this.pointRecordMapper = pointRecordMapper;
        this.riskGuard = riskGuard;
        this.pointsApi = pointsApi;
        this.couponApi = couponApi;
        this.rightsApi = rightsApi;
        this.businessMessages = businessMessages;
    }

    // ---------------------------------------------------------------- 邀请码

    @Override
    @Transactional
    public String ensureInviteCode(long userId, Integer channel, String deviceId, String ip,
                                   String phoneSegment) {
        InviteCode existing = codeMapper.selectOne(Wrappers.<InviteCode>lambdaQuery()
                .eq(InviteCode::getUserId, userId));
        if (existing != null) {
            return existing.getCode();
        }
        InviteCode code = new InviteCode();
        code.setUserId(userId);
        code.setChannel(channel == null ? InviteRelation.CHANNEL_LINK : channel);
        code.setOwnerDeviceId(Text.trimToNull(deviceId));
        code.setOwnerIp(Text.trimToNull(ip));
        code.setOwnerPhoneSegment(normalizeSegment(phoneSegment));
        for (int attempt = 0; attempt < 5; attempt++) {
            code.setCode(randomCode());
            try {
                codeMapper.insert(code);
                return code.getCode();
            } catch (DuplicateKeyException e) {
                // 码撞了（8 位 31 字符集，撞的概率极低）就换一个；用户 id 撞了说明并发创建，
                // 直接回读那一条（一人一码由唯一键兜底）
                InviteCode concurrent = codeMapper.selectOne(Wrappers.<InviteCode>lambdaQuery()
                        .eq(InviteCode::getUserId, userId));
                if (concurrent != null) {
                    return concurrent.getCode();
                }
            }
        }
        throw BusinessException.conflict("邀请码生成失败，请重试");
    }

    // ---------------------------------------------------------------- 归因

    @Override
    @Transactional
    public Attribution attribute(AttributionCommand command) {
        String raw = command.inviteCode() == null ? "" : command.inviteCode().trim().toUpperCase(java.util.Locale.ROOT);
        if (raw.isEmpty()) {
            return Attribution.rejected("CODE_NOT_FOUND");
        }
        InviteCode inviteCode = codeMapper.selectOne(Wrappers.<InviteCode>lambdaQuery()
                .eq(InviteCode::getCode, raw));
        if (inviteCode == null) {
            return Attribution.rejected("CODE_NOT_FOUND");
        }
        InviteRelation existing = relationMapper.selectOne(Wrappers.<InviteRelation>lambdaQuery()
                .eq(InviteRelation::getInviteeUserId, command.inviteeUserId()));
        if (existing != null) {
            // 归因只做一次：注册重试、用户手滑都不该改写归因（不做事后补填的同一口径）
            return Attribution.rejected("ALREADY_ATTRIBUTED");
        }

        Optional<String> hit = riskGuard.judge(inviteCode, command.inviteeUserId(),
                command.deviceId(), command.ip(), normalizeSegment(command.phoneSegment()));
        if (hit.isPresent()) {
            return Attribution.rejected(hit.get());
        }

        InviteRelation relation = new InviteRelation();
        relation.setInviterUserId(inviteCode.getUserId());
        relation.setInviteeUserId(command.inviteeUserId());
        relation.setInviteCode(inviteCode.getCode());
        relation.setChannel(command.channel() == null ? InviteRelation.CHANNEL_LINK : command.channel());
        relation.setStatus(InviteRelation.STATUS_PENDING);
        relation.setAttributedAt(command.registeredAt() == null ? AppTime.now() : command.registeredAt());
        try {
            relationMapper.insert(relation);
        } catch (DuplicateKeyException e) {
            return Attribution.rejected("ALREADY_ATTRIBUTED");
        }
        return Attribution.ok(relation.getId());
    }

    // ---------------------------------------------------------------- 建档与结算

    @Override
    @Transactional
    public boolean markProfileCompleted(long inviteeUserId) {
        InviteRelation relation = relationMapper.selectOne(Wrappers.<InviteRelation>lambdaQuery()
                .eq(InviteRelation::getInviteeUserId, inviteeUserId)
                .eq(InviteRelation::getStatus, InviteRelation.STATUS_PENDING));
        if (relation == null) {
            return false;
        }
        if (relation.getProfileCompletedAt() == null) {
            relation.setProfileCompletedAt(AppTime.now());
            relationMapper.updateById(relation);
        }
        return true;
    }

    /**
     * 结算一批到期的关系（观察窗已满）。
     *
     * <p>判据：观察窗（24 小时）内该被邀请人**有没有行为**。有 → 有效（计数 + 发积分 + 查阶梯）；
     * 没有 → 无效（记反作弊记录，不计数不发奖）。
     *
     * <p>「有行为」的证据是**积分流水**（本模块可见的唯一跨模块行为信号），
     * 且**排除「完成建档」本身**——它是触发条件，拿它当活跃证据等于让这条规则自动失效。
     */
    @Override
    @Transactional
    public SettlementResult settle(int limit) {
        LocalDateTime now = AppTime.now();
        LocalDateTime deadline = now.minusHours(InviteRelation.OBSERVE_HOURS);
        List<InviteRelation> candidates = relationMapper.findSettleable(deadline, limit);
        int effective = 0;
        int invalid = 0;
        for (InviteRelation relation : candidates) {
            LocalDateTime windowStart = relation.getAttributedAt();
            LocalDateTime windowEnd = windowStart.plusHours(InviteRelation.OBSERVE_HOURS);
            Long activity = pointRecordMapper.findAnyActivity(relation.getInviteeUserId(),
                    windowStart, windowEnd, PointBehavior.PROFILE_COMPLETE);
            if (activity == null) {
                relation.setStatus(InviteRelation.STATUS_INVALID);
                relation.setRejectReason(InviteRelation.REJECT_NO_ACTIVITY);
                relation.setSettledAt(now);
                relationMapper.updateById(relation);
                riskGuard.recordNoActivity(relation.getInviterUserId(), relation.getInviteeUserId());
                invalid++;
                continue;
            }
            relation.setStatus(InviteRelation.STATUS_EFFECTIVE);
            relation.setRejectReason(null);
            relation.setSettledAt(now);
            relationMapper.updateById(relation);
            effective++;
            afterEffective(relation);
        }
        if (!candidates.isEmpty()) {
            log.info("邀请结算完成：扫描 {} 条，有效 {} 条，无效 {} 条", candidates.size(), effective, invalid);
        }
        return new SettlementResult(candidates.size(), effective, invalid);
    }

    /**
     * 一条邀请变成「有效」之后的两件事：给邀请人发积分、检查阶梯。
     *
     * <p>积分用 {@code sourceRef = invite:{关系 id}} 做幂等（同一邀请只发一次 20 分）；
     * 阶梯奖用 {@code (user_id, threshold)} 唯一兜底。
     */
    /**
     * 一次邀请判定有效之后的三件事：给邀请人发分、给被邀请人也发一份、结算邀请人的阶梯。
     *
     * <p>**被邀请人那一份默认不发**（行为码 `INVITE_INVITEE` 的种子是「分值 0 + 停用」）：
     * 交付文档写「双方各得」，但「得多少、得什么」是产品决策——机制先落地，运营配了分值与启用
     * 才会真的发（ADR-0046 第五节：奖励物留空是有效状态）。
     *
     * <p>时机与被邀请人的「入账」绑在**同一刻**（观察窗结束、关系判有效），不是注册即发：
     * 注册即发会让批量刷号当晚就领到奖励，而观察窗（ADR-0039 第三层）正是为拦住它设计的。
     * 发分失败不影响邀请人那一份：两笔账各自幂等（`sourceRef` 分别是 `invite:` 与 `invitee:`）。
     */
    private void afterEffective(InviteRelation relation) {
        pointsApi.award(new PointsApi.AwardCommand(relation.getInviterUserId(), PointBehavior.INVITE,
                "invite:" + relation.getId(), "有效邀请：" + relation.getInviteeUserId()));
        pointsApi.award(new PointsApi.AwardCommand(relation.getInviteeUserId(), PointBehavior.INVITE_INVITEE,
                "invitee:" + relation.getId(), "被邀请注册"));
        grantLadderRewards(relation.getInviterUserId());
        notifyInviter(relation);
    }

    /**
     * 给邀请人发一条进度通知（F026「邀请进度实时更新」的站内版本）。
     *
     * <p>消息中心是**拉取式**的（没有 WebSocket / SSE，ADR-0042 把实时通道后置了），
     * 所以这里做的是「事件发生时把进度写进消息中心」——用户下次打开消息中心就看到，
     * 而不是靠前端轮询一个每秒都可能变的数字。进度数字现算（{@code countEffectiveOf}），
     * 不缓存：这个数只在这一条消息里用一次。
     *
     * <p>去重键按关系 id：同一条邀请被重复结算也只会有一条消息（{@code settle} 本身也幂等）。
     */
    private void notifyInviter(InviteRelation relation) {
        int effective = relationMapper.countEffectiveOf(relation.getInviterUserId());
        businessMessages.notify(new BusinessMessageApi.BusinessNotification(
                relation.getInviterUserId(),
                BusinessMessageApi.TYPE_INVITE,
                "invite-effective-" + relation.getId(),
                "邀请生效了",
                "你邀请的好友已完成建档并在观察期内有记录，有效邀请 +1（当前有效 " + effective + " 人）。",
                "看邀请进度",
                "/invites"));
    }

    /**
     * 检查并发放阶梯奖励（阶梯 1 / 3 / 5 / 10 / 15）。
     *
     * <p>每个已达成的档位只发一次（唯一键兜底）；**没配奖励的档位照样记录达成**，只是不发东西
     * （「谁在哪一档」是数据，「发什么」是配置）。
     */
    @Transactional
    public void grantLadderRewards(long inviterUserId) {
        int effectiveCount = relationMapper.countEffectiveOf(inviterUserId);
        // 档位的读法与 C 端展示**共用一处**（InviteLadder）：两处各写一次的结果是
        // 「C 端显示了一个永远不会发的奖励档位」——见 InviteLadder 的类注释
        List<InviteLadderTier> tiers = InviteLadder.enabledTiers(tierMapper).stream()
                .filter(tier -> tier.getThreshold() != null && tier.getThreshold() <= effectiveCount)
                .toList();
        for (InviteLadderTier tier : tiers) {
            InviteLadderAchievement existing = achievementMapper.selectOne(
                    Wrappers.<InviteLadderAchievement>lambdaQuery()
                            .eq(InviteLadderAchievement::getUserId, inviterUserId)
                            .eq(InviteLadderAchievement::getThreshold, tier.getThreshold()));
            if (existing != null) {
                continue; // 这一档已经发过（阶梯奖只在达到门槛时发一次）
            }
            InviteLadderAchievement achievement = new InviteLadderAchievement();
            achievement.setUserId(inviterUserId);
            achievement.setThreshold(tier.getThreshold());
            achievement.setEffectiveCount(effectiveCount);
            achievement.setRewardType(tier.hasReward() ? tier.getRewardType() : null);
            achievement.setAchievedAt(AppTime.now());

            // 发奖失败**不能**拖垮结算：有效邀请的计数是硬的，奖励物是可配的。
            // 一处配置错误（券模板停了、权益码被删了）如果抛出去，整个结算事务回滚，
            // 那一批邀请就会永远停在「待生效」，而且每小时重试一次、每次都失败。
            // 这里失败就不写达成记录 —— 记录是「这一档发过了」的唯一凭据，
            // 不写它，运营修好配置后下一次批算会把这份奖励补上。
            boolean rewardGranted = true;
            if (tier.hasReward() && tier.getRewardType() != null
                    && tier.getRewardType() == InviteLadderTier.REWARD_COUPON
                    && tier.getCouponTemplateId() != null) {
                String ref = "invite-ladder:" + inviterUserId + ":" + tier.getThreshold();
                int count = tier.getRewardCount() == null ? 1 : tier.getRewardCount();
                try {
                    for (int i = 0; i < count; i++) {
                        CouponApi.CouponInfo coupon = couponApi.issue(new CouponApi.IssueCommand(
                                inviterUserId, tier.getCouponTemplateId(), Coupon.SOURCE_INVITE,
                                count == 1 ? ref : ref + ":" + (i + 1), null,
                                "邀请阶梯 " + tier.getThreshold()));
                        // 一次达成发多张时只把第一张记进达成记录：其余张也能从券的 source_ref 追到
                        if (achievement.getCouponId() == null) {
                            achievement.setCouponId(coupon.id());
                        }
                    }
                } catch (RuntimeException e) {
                    rewardGranted = false;
                    log.warn("邀请阶梯 {} 的奖励发放失败（达成记录不写，修好配置后会自动补发）：userId={} 原因={}",
                            tier.getThreshold(), inviterUserId, e.getMessage());
                }
            } else if (tier.hasReward() && tier.getRewardType() != null
                    && tier.getRewardType() == InviteLadderTier.REWARD_RIGHTS
                    && tier.getRightsCode() != null) {
                // source_ref 与阶梯一一对应（invite-ladder:{用户}:{门槛}），
                // 所以这里不存授予记录的 id 也能追到「这一档给了哪条权益」——
                // 授予接口是幂等的（同一来源引用只授一次），重跑不会多发一条
                try {
                    rightsApi.grant(new RightsApi.GrantCommand(inviterUserId, tier.getRightsCode(),
                            RightsGrant.SOURCE_INVITE,
                            "invite-ladder:" + inviterUserId + ":" + tier.getThreshold(),
                            null, "邀请阶梯 " + tier.getThreshold()));
                    log.info("邀请阶梯授予权益：userId={} code={} threshold={}", inviterUserId,
                            tier.getRightsCode(), tier.getThreshold());
                } catch (RuntimeException e) {
                    rewardGranted = false;
                    log.warn("邀请阶梯 {} 的权益授予失败（达成记录不写，修好配置后会自动补发）：userId={} 原因={}",
                            tier.getThreshold(), inviterUserId, e.getMessage());
                }
            }

            if (!rewardGranted) {
                continue;
            }

            try {
                achievementMapper.insert(achievement);
            } catch (DuplicateKeyException e) {
                log.debug("阶梯达成记录并发重复，跳过：userId={} threshold={}", inviterUserId, tier.getThreshold());
            }
        }
    }

    // ---------------------------------------------------------------- 内部

    private static String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_ALPHABET[RANDOM.nextInt(CODE_ALPHABET.length)]);
        }
        return sb.toString();
    }

    /** 号段归一化：只保留数字（手机号前 7 位），非手机号一律不存。 */
    private static String normalizeSegment(String value) {
        if (value == null) {
            return null;
        }
        String digits = value.replaceAll("\\D", "");
        return digits.length() >= 7 ? digits.substring(0, 7) : null;
    }
}
