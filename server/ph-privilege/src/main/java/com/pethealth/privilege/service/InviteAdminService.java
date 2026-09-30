package com.pethealth.privilege.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pethealth.api.privilege.InviteDtos;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.privilege.domain.CouponTemplate;
import com.pethealth.privilege.domain.InviteCode;
import com.pethealth.privilege.domain.InviteLadderTier;
import com.pethealth.privilege.domain.InviteRelation;
import com.pethealth.privilege.domain.InviteRiskRecord;
import com.pethealth.privilege.domain.RightsCode;
import com.pethealth.privilege.mapper.CouponTemplateMapper;
import com.pethealth.privilege.mapper.InviteCodeMapper;
import com.pethealth.privilege.mapper.InviteLadderAchievementMapper;
import com.pethealth.privilege.mapper.InviteLadderTierMapper;
import com.pethealth.privilege.mapper.InviteRelationMapper;
import com.pethealth.privilege.mapper.InviteRiskRecordMapper;
import com.pethealth.privilege.mapper.RightsCodeMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 邀请的运营侧（切片 #111）：总览、关系查询、阶梯档位配置、反作弊记录。
 *
 * <p>总览里给的是**有效邀请转化率**（有效 ÷ 注册），不是交付文档的「K 因子 &gt; 1」——
 * 三个端改成 Web 之后没有小程序的社交转发链，那个指标不成立（ADR-0039 第一节）。
 * 这条要与验收方说明。
 *
 * <p>阶梯档位的配置只改**奖励物**：门槛是产品定的五档（1/3/5/10/15），
 * 改门槛等于重新定义阶梯；而「谁在哪一档」的历史记录不该被一次配置改动改写。
 */
@Service
public class InviteAdminService {

    /** 阶梯门槛固定五档（ADR-0039）。 */
    private static final List<Integer> THRESHOLDS = List.of(1, 3, 5, 10, 15);

    private final InviteCodeMapper codeMapper;
    private final InviteRelationMapper relationMapper;
    private final InviteLadderTierMapper tierMapper;
    private final InviteLadderAchievementMapper achievementMapper;
    private final InviteRiskRecordMapper riskMapper;
    private final CouponTemplateMapper couponTemplateMapper;
    private final RightsCodeMapper rightsCodeMapper;

    public InviteAdminService(InviteCodeMapper codeMapper, InviteRelationMapper relationMapper,
                              InviteLadderTierMapper tierMapper,
                              InviteLadderAchievementMapper achievementMapper,
                              InviteRiskRecordMapper riskMapper,
                              CouponTemplateMapper couponTemplateMapper,
                              RightsCodeMapper rightsCodeMapper) {
        this.codeMapper = codeMapper;
        this.relationMapper = relationMapper;
        this.tierMapper = tierMapper;
        this.achievementMapper = achievementMapper;
        this.riskMapper = riskMapper;
        this.couponTemplateMapper = couponTemplateMapper;
        this.rightsCodeMapper = rightsCodeMapper;
    }

    /** 邀请总览（含阶梯达成统计）。 */
    @Transactional(readOnly = true)
    public InviteDtos.InviteOverviewView overview() {
        CurrentUser.requireAdmin();
        long inviteCodes = codeMapper.selectCount(Wrappers.<InviteCode>lambdaQuery());
        int registered = relationMapper.selectCount(Wrappers.<InviteRelation>lambdaQuery())
                .intValue();
        int pending = relationMapper.countByStatus(InviteRelation.STATUS_PENDING);
        int effective = relationMapper.countByStatus(InviteRelation.STATUS_EFFECTIVE);
        int invalid = relationMapper.countByStatus(InviteRelation.STATUS_INVALID);

        List<InviteDtos.InviteLadderStatView> ladderStats = THRESHOLDS.stream()
                .map(threshold -> new InviteDtos.InviteLadderStatView(threshold,
                        achievementMapper.countByThreshold(threshold)))
                .toList();
        return new InviteDtos.InviteOverviewView((int) inviteCodes, registered, pending, effective, invalid,
                rate(effective, registered), ladderStats);
    }

    /** 邀请关系分页。 */
    @Transactional(readOnly = true)
    public PageResult<InviteDtos.InviteRelationView> listRelations(Long inviterUserId, Long inviteeUserId,
                                                                   Integer status, long page, long pageSize) {
        CurrentUser.requireAdmin();
        Page<InviteRelation> result = relationMapper.selectPage(new Page<>(page, pageSize),
                Wrappers.<InviteRelation>lambdaQuery()
                        .eq(inviterUserId != null, InviteRelation::getInviterUserId, inviterUserId)
                        .eq(inviteeUserId != null, InviteRelation::getInviteeUserId, inviteeUserId)
                        .eq(status != null, InviteRelation::getStatus, status)
                        .orderByDesc(InviteRelation::getAttributedAt)
                        .orderByDesc(InviteRelation::getId));
        return PageResult.from(result, PrivilegeViews::toRelationView);
    }

    /** 阶梯档位（含未配奖励的档位）。 */
    @Transactional(readOnly = true)
    public List<InviteDtos.InviteLadderTierView> listLadderTiers() {
        CurrentUser.requireAdmin();
        List<InviteLadderTier> tiers = tierMapper.selectList(Wrappers.<InviteLadderTier>lambdaQuery()
                .orderByAsc(InviteLadderTier::getThreshold));
        Map<Long, CouponTemplate> templates = templatesOf(tiers);
        Map<String, RightsCode> codes = codesOf(tiers);
        return tiers.stream().map(tier -> PrivilegeViews.toLadderTierView(tier,
                tier.getCouponTemplateId() == null ? null : templates.get(tier.getCouponTemplateId()),
                tier.getRightsCode() == null ? null : codes.get(tier.getRightsCode()))).toList();
    }

    /** 配置某一档的奖励（门槛不可改）。 */
    @Transactional
    public InviteDtos.InviteLadderTierView updateLadderTier(int threshold,
                                                            InviteDtos.InviteLadderTierRequest request) {
        CurrentUser.requireAdmin();
        InviteLadderTier tier = tierMapper.selectOne(Wrappers.<InviteLadderTier>lambdaQuery()
                .eq(InviteLadderTier::getThreshold, threshold));
        if (tier == null) {
            throw BusinessException.notFound("该阶梯档位不存在（门槛是固定五档 1/3/5/10/15）");
        }
        CouponTemplate template = null;
        RightsCode code = null;
        if (request.rewardType() != null) {
            if (request.rewardType() == InviteLadderTier.REWARD_COUPON) {
                if (request.couponTemplateId() == null) {
                    throw BusinessException.paramInvalid("发券的档位必须指定券模板");
                }
                template = couponTemplateMapper.selectById(request.couponTemplateId());
                if (template == null || !template.isEnabled()) {
                    throw BusinessException.paramInvalid("券模板不存在或已停用");
                }
            } else {
                if (request.rightsCode() == null || request.rightsCode().isBlank()) {
                    throw BusinessException.paramInvalid("授权益的档位必须指定权益码");
                }
                code = rightsCodeMapper.selectOne(Wrappers.<RightsCode>lambdaQuery()
                        .eq(RightsCode::getCode, request.rightsCode().trim()));
                if (code == null) {
                    throw BusinessException.paramInvalid("权益码不存在：" + request.rightsCode());
                }
            }
        }
        tier.setRewardType(request.rewardType());
        tier.setCouponTemplateId(request.rewardType() != null
                && request.rewardType() == InviteLadderTier.REWARD_COUPON ? request.couponTemplateId() : null);
        tier.setRightsCode(request.rewardType() != null
                && request.rewardType() == InviteLadderTier.REWARD_RIGHTS ? code.getCode() : null);
        tier.setRewardCount(request.rewardCount() == null ? 1 : request.rewardCount());
        tier.setStatus(request.status());
        tierMapper.updateById(tier);
        return PrivilegeViews.toLadderTierView(tier, template, code);
    }

    /** 反作弊拦截记录分页。 */
    @Transactional(readOnly = true)
    public PageResult<InviteDtos.InviteRiskRecordView> listRiskRecords(String rule, long page, long pageSize) {
        CurrentUser.requireAdmin();
        Page<InviteRiskRecord> result = riskMapper.selectPage(new Page<>(page, pageSize),
                Wrappers.<InviteRiskRecord>lambdaQuery()
                        .eq(rule != null && !rule.isBlank(), InviteRiskRecord::getRule,
                                rule == null ? null : rule.trim())
                        .orderByDesc(InviteRiskRecord::getCreatedAt)
                        .orderByDesc(InviteRiskRecord::getId));
        return PageResult.from(result, PrivilegeViews::toRiskRecordView);
    }

    private Map<Long, CouponTemplate> templatesOf(List<InviteLadderTier> tiers) {
        List<Long> ids = tiers.stream().map(InviteLadderTier::getCouponTemplateId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, CouponTemplate> map = new HashMap<>();
        if (ids.isEmpty()) {
            return map;
        }
        for (CouponTemplate template : couponTemplateMapper.selectBatchIds(ids)) {
            map.put(template.getId(), template);
        }
        return map;
    }

    private Map<String, RightsCode> codesOf(List<InviteLadderTier> tiers) {
        List<String> codes = tiers.stream().map(InviteLadderTier::getRightsCode)
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<String, RightsCode> map = new HashMap<>();
        if (codes.isEmpty()) {
            return map;
        }
        for (RightsCode code : rightsCodeMapper.selectList(Wrappers.<RightsCode>lambdaQuery()
                .in(RightsCode::getCode, codes))) {
            map.put(code.getCode(), code);
        }
        return map;
    }

    /** 有效率：有效 ÷ 注册（两位小数字符串）；没有注册时给 0.00（不是 null——null 会被当成「未计入」）。 */
    private static String rate(int effective, int registered) {
        if (registered <= 0) {
            return "0.00";
        }
        return BigDecimal.valueOf(effective)
                .divide(BigDecimal.valueOf(registered), 2, RoundingMode.HALF_UP)
                .toPlainString();
    }
}
