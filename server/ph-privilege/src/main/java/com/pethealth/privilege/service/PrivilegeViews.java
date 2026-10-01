package com.pethealth.privilege.service;

import com.pethealth.api.privilege.CouponDtos;
import com.pethealth.api.privilege.InviteDtos;
import com.pethealth.api.privilege.PointsDtos;
import com.pethealth.api.privilege.RightsDtos;
import com.pethealth.catalog.api.CatalogQueryApi;
import com.pethealth.catalog.api.Price;
import com.pethealth.privilege.domain.Coupon;
import com.pethealth.privilege.domain.CouponContribution;
import com.pethealth.privilege.domain.CouponContributionLog;
import com.pethealth.privilege.domain.CouponTemplate;
import com.pethealth.privilege.domain.InviteLadderTier;
import com.pethealth.privilege.domain.InviteRelation;
import com.pethealth.privilege.domain.InviteRiskRecord;
import com.pethealth.privilege.domain.PointBehavior;
import com.pethealth.privilege.domain.PointConfig;
import com.pethealth.privilege.domain.PointExchangeOption;
import com.pethealth.privilege.domain.PointLadderTier;
import com.pethealth.privilege.domain.PointRecord;
import com.pethealth.privilege.domain.PointTask;
import com.pethealth.privilege.domain.RightsCode;
import com.pethealth.privilege.domain.RightsGrant;

import java.math.BigDecimal;
import java.util.List;

/**
 * 实体 → 契约视图的映射。**只在这一个类里做**（与 {@code ProviderViews} 同一取舍）：
 *
 * <ul>
 *   <li><b>金额格式只有一处</b>：所有金额出接口前必须过 {@link Price#format}（两位小数字符串，
 *       ADR-0011：JS 里丢精度）。散在各处早晚会有一处直接 {@code toString()} 出个
 *       {@code 30.0} 或科学计数法；
 *   <li><b>跨模块拼装只有一处</b>：券的适用范围要用目录侧的编码 → 名字（ADR-0006 的接口调用），
 *       拼装规则写在这里，service 只管业务分支；
 *   <li><b>「只在某一侧有值」的字段只有一处</b>：服务者的券实例列表不带领券人身份、
 *       运营侧才带门店名——这类差异写在映射里比写在 SQL 里好读。
 * </ul>
 *
 * <p>本类不含任何脱敏逻辑：券池 / 邀请 / 积分的表里**没有**需要脱敏的个人信息
 * （邀请码只存码主人手机号的**前 7 位**号段，那是判据不是身份）。
 */
final class PrivilegeViews {

    /** 来源的展示名（与 ADR-0037 / ADR-0038 的枚举一一对应）。 */
    static String sourceName(Integer source) {
        if (source == null) {
            return null;
        }
        return switch (source) {
            case Coupon.SOURCE_INVITE -> "邀请";
            case Coupon.SOURCE_CHECK_IN_TASK -> "打卡任务";
            case Coupon.SOURCE_POINTS_EXCHANGE -> "积分兑换";
            case Coupon.SOURCE_PLATFORM_SUBSIDY -> "平台补贴";
            case Coupon.SOURCE_MONTHLY_LADDER -> "月度阶梯";
            default -> "未知来源";
        };
    }

    /** 权益来源的展示名（判定视图要给出「这条是哪来的」）。 */
    static String rightsSourceName(Integer source) {
        if (source == null) {
            return null;
        }
        return switch (source) {
            case RightsGrant.SOURCE_SUBSCRIPTION -> "订阅";
            case RightsGrant.SOURCE_INVITE -> "邀请";
            case RightsGrant.SOURCE_CHECK_IN -> "打卡";
            case RightsGrant.SOURCE_COMPENSATION -> "运营补偿";
            default -> "未知来源";
        };
    }

    private PrivilegeViews() {
    }

    // ---------------------------------------------------------------- 券池

    static CouponDtos.CouponTemplateView toTemplateView(CouponTemplate template, int issuedCount,
                                                       CatalogQueryApi catalog) {
        return new CouponDtos.CouponTemplateView(
                template.getId(),
                template.getCode(),
                template.getName(),
                Price.format(template.getFaceValue()),
                Price.format(template.getMinAmount()),
                template.getValidDays(),
                template.getCostBearer(),
                template.getScopeType(),
                template.scopeCodeList(),
                scopeDesc(template, catalog),
                template.getIssueLimit(),
                issuedCount,
                template.getStatus(),
                template.getDescription(),
                template.getCreatedAt(),
                template.getUpdatedAt());
    }

    static CouponDtos.CouponContributionView toContributionView(CouponContribution contribution,
                                                                CouponTemplate template,
                                                                CouponQuota quota,
                                                                String providerName,
                                                                String scopeDesc,
                                                                List<CouponContributionLog> logs) {
        return new CouponDtos.CouponContributionView(
                contribution.getId(),
                contribution.getProviderId(),
                providerName,
                contribution.getTemplateId(),
                template == null ? null : template.getCode(),
                template == null ? null : template.getName(),
                template == null ? null : Price.format(template.getFaceValue()),
                template == null ? null : Price.format(template.getMinAmount()),
                scopeDesc,
                quota.totalCount(),
                quota.issuedCount(),
                quota.redeemedCount(),
                quota.reservedCount(),
                quota.expiredCount(),
                quota.available(),
                quota.completionRate(),
                contribution.getStatus(),
                contribution.getRemark(),
                logs == null ? null : logs.stream().map(PrivilegeViews::toLogView).toList(),
                contribution.getCreatedAt(),
                contribution.getUpdatedAt());
    }

    static CouponDtos.CouponContributionLogView toLogView(CouponContributionLog log) {
        return new CouponDtos.CouponContributionLogView(
                log.getId(),
                log.getAction(),
                log.getTotalCount(),
                log.getRemark(),
                log.getCreatedBy(),
                log.getCreatedAt());
    }

    static CouponDtos.CouponView toCouponView(Coupon coupon, CouponTemplate template, String providerName) {
        return toCouponView(coupon, template, providerName, null, null);
    }

    /**
     * 同上，但带上「这一单能不能用 / 是不是最优」两个判定（C 端券列表在带了门店与金额时才有）。
     *
     * <p>两处都是 {@code null} 表示**没判**——与 {@code false}（判过、不能用）不是一回事，
     * 前端据此决定要不要显示「不适用」。
     */
    static CouponDtos.CouponView toCouponView(Coupon coupon, CouponTemplate template, String providerName,
                                              Boolean applies, Boolean recommended) {
        return new CouponDtos.CouponView(
                coupon.getId(),
                coupon.getCode(),
                coupon.getUserId(),
                coupon.getProviderId(),
                providerName,
                coupon.getTemplateId(),
                template == null ? null : template.getCode(),
                template == null ? null : template.getName(),
                Price.format(coupon.getFaceValue()),
                Price.format(coupon.getMinAmount()),
                coupon.getSource(),
                coupon.getContributionId(),
                coupon.getStatus(),
                coupon.getValidFrom(),
                coupon.getValidUntil(),
                coupon.getIssuedAt(),
                coupon.getRedeemedAt(),
                coupon.getCreatedAt(),
                applies,
                recommended);
    }

    /**
     * 适用范围的现成文案。
     *
     * <p>为什么由服务端拼：编码（{@code HOSPITAL} / {@code HE-004}）是给系统看的，
     * 运营与服务者要看的是「限分类：医院、洗护美容」。前端各拼一遍的话，
     * 目录里改了名字不会自动跟上（而名字是现取的，本来就该跟着变）。
     */
    static String scopeDesc(CouponTemplate template, CatalogQueryApi catalog) {
        int scopeType = template.getScopeType() == null ? CouponTemplate.SCOPE_NONE : template.getScopeType();
        List<String> codes = template.scopeCodeList();
        if (scopeType == CouponTemplate.SCOPE_NONE || codes.isEmpty()) {
            return "全部服务";
        }
        if (scopeType == CouponTemplate.SCOPE_CATEGORY) {
            var names = catalog.categoryNames(codes);
            return "限分类：" + codes.stream().map(code -> names.getOrDefault(code, code)).reduce(
                    (a, b) -> a + "、" + b).orElse("");
        }
        var names = catalog.findItems(codes);
        return "限项目：" + codes.stream()
                .map(code -> {
                    CatalogQueryApi.ItemInfo item = names.get(code);
                    return item == null ? code : item.name();
                })
                .reduce((a, b) -> a + "、" + b).orElse("");
    }

    /** 模板快照：把模板上的面额 / 门槛 / 适用范围抄进券实例（券是平台对用户的承诺，不能随后改）。 */
    static void copySnapshot(CouponTemplate template, Coupon coupon, java.time.LocalDateTime now) {
        coupon.setFaceValue(template.getFaceValue());
        coupon.setMinAmount(template.getMinAmount() == null ? BigDecimal.ZERO : template.getMinAmount());
        coupon.setScopeType(template.getScopeType() == null ? CouponTemplate.SCOPE_NONE : template.getScopeType());
        coupon.setScopeCodes(template.getScopeCodes());
        coupon.setValidFrom(now);
        coupon.setValidUntil(now.plusDays(template.getValidDays() == null ? 30 : template.getValidDays()));
        coupon.setIssuedAt(now);
    }

    // ---------------------------------------------------------------- 权益

    static RightsDtos.RightsCodeView toRightsCodeView(RightsCode code) {
        return new RightsDtos.RightsCodeView(
                code.getCode(),
                code.getName(),
                code.getDescription(),
                code.getSortOrder(),
                code.getStatus(),
                code.getCreatedAt(),
                code.getUpdatedAt());
    }

    static RightsDtos.RightsGrantView toGrantView(RightsGrant grant, RightsCode code) {
        return new RightsDtos.RightsGrantView(
                grant.getId(),
                grant.getUserId(),
                grant.getCode(),
                code == null ? null : code.getName(),
                grant.getSource(),
                grant.getExpireAt(),
                grant.getStatus(),
                grant.getSourceRef(),
                grant.getRemark(),
                grant.getCreatedAt(),
                grant.getRevokedAt());
    }

    static RightsDtos.RightsItemView toRightsItemView(RightsCode code, boolean effective, Integer source,
                                                      java.time.LocalDateTime expireAt) {
        return new RightsDtos.RightsItemView(
                code.getCode(),
                code.getName(),
                effective,
                source,
                rightsSourceName(source),
                expireAt);
    }

    // ---------------------------------------------------------------- 邀请

    static InviteDtos.InviteRelationView toRelationView(InviteRelation relation) {
        return new InviteDtos.InviteRelationView(
                relation.getId(),
                relation.getInviterUserId(),
                relation.getInviteeUserId(),
                relation.getInviteCode(),
                relation.getChannel(),
                relation.getStatus(),
                relation.getRejectReason(),
                relation.getAttributedAt(),
                relation.getProfileCompletedAt(),
                relation.getSettledAt(),
                relation.getCreatedAt());
    }

    static InviteDtos.InviteLadderTierView toLadderTierView(InviteLadderTier tier, CouponTemplate template,
                                                            RightsCode rightsCode) {
        return new InviteDtos.InviteLadderTierView(
                tier.getThreshold(),
                tier.getRewardType(),
                tier.getCouponTemplateId(),
                template == null ? null : template.getName(),
                tier.getRightsCode(),
                rightsCode == null ? null : rightsCode.getName(),
                tier.getRewardCount(),
                tier.getStatus(),
                tier.getUpdatedAt());
    }

    static InviteDtos.InviteRiskRecordView toRiskRecordView(InviteRiskRecord record) {
        return new InviteDtos.InviteRiskRecordView(
                record.getId(),
                record.getRule(),
                record.getInviterUserId(),
                record.getInviteeUserId(),
                record.getDeviceId(),
                record.getIp(),
                record.getDetail(),
                record.getCreatedAt());
    }

    // ---------------------------------------------------------------- 积分

    static PointsDtos.PointBehaviorView toBehaviorView(PointBehavior behavior) {
        return new PointsDtos.PointBehaviorView(
                behavior.getCode(),
                behavior.getName(),
                behavior.getPoints(),
                behavior.getCountsTowardDailyCap(),
                behavior.getDailyCountLimit(),
                behavior.getMonthlyCountLimit(),
                behavior.getOnceOnly(),
                behavior.getStatus(),
                behavior.getSortOrder(),
                behavior.getUpdatedAt());
    }

    static PointsDtos.PointTaskView toTaskView(PointTask task, PointBehavior behavior) {
        return new PointsDtos.PointTaskView(
                task.getId(),
                task.getCode(),
                task.getName(),
                task.getPeriod(),
                task.getBehaviorCode(),
                behavior == null ? null : behavior.getName(),
                task.getTargetCount(),
                behavior == null ? null : behavior.getPoints(),
                task.getSortOrder(),
                task.getStatus(),
                task.getUpdatedAt());
    }

    static PointsDtos.PointExchangeOptionView toExchangeOptionView(PointExchangeOption option,
                                                                   CouponTemplate template) {
        return new PointsDtos.PointExchangeOptionView(
                option.getId(),
                option.getName(),
                option.getPointsCost(),
                option.getCouponTemplateId(),
                template == null ? null : template.getName(),
                template == null ? null : Price.format(template.getFaceValue()),
                option.getSortOrder(),
                option.getStatus(),
                option.getUpdatedAt());
    }

    static PointsDtos.PointLadderTierView toLadderTierView(PointLadderTier tier, CouponTemplate template) {
        return new PointsDtos.PointLadderTierView(
                tier.getId(),
                tier.getThresholdPoints(),
                tier.getCouponTemplateId(),
                template == null ? null : template.getName(),
                tier.getCouponCount(),
                tier.getSortOrder(),
                tier.getStatus(),
                tier.getUpdatedAt());
    }

    static PointsDtos.PointRecordView toRecordView(PointRecord record, PointBehavior behavior) {
        return new PointsDtos.PointRecordView(
                record.getId(),
                record.getUserId(),
                record.getBehaviorCode(),
                behavior == null ? null : behavior.getName(),
                record.getChangeAmount(),
                record.getBalanceAfter(),
                record.getCountsTowardDailyCap(),
                record.getSourceRef(),
                record.getRemark(),
                record.getCreatedAt());
    }

    static PointsDtos.PointsOverviewView toPointsOverview(PointConfig config, com.pethealth.privilege.domain.PointStat stat) {
        return new PointsDtos.PointsOverviewView(
                intOf(stat == null ? null : stat.getAccounts()),
                intOf(stat == null ? null : stat.getBalanceTotal()),
                intOf(stat == null ? null : stat.getEarnedTotal()),
                intOf(stat == null ? null : stat.getSpentTotal()),
                intOf(stat == null ? null : stat.getTodayEarned()),
                intOf(stat == null ? null : stat.getTodayUsers()),
                config == null ? 20 : config.getDailyEarnLimit());
    }

    private static int intOf(Integer value) {
        return value == null ? 0 : value;
    }
}
