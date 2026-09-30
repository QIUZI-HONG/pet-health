package com.pethealth.privilege.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pethealth.api.privilege.CouponDtos;
import com.pethealth.catalog.api.CatalogQueryApi;
import com.pethealth.catalog.api.Price;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.time.AppTime;
import com.pethealth.privilege.domain.Coupon;
import com.pethealth.privilege.domain.CouponContribution;
import com.pethealth.privilege.api.CouponApi;
import com.pethealth.privilege.domain.CouponStat;
import com.pethealth.privilege.domain.CouponTemplate;
import com.pethealth.privilege.mapper.CouponContributionMapper;
import com.pethealth.privilege.mapper.CouponMapper;
import com.pethealth.privilege.mapper.CouponTemplateMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 券模板与券池总览（运营侧），决策见 ADR-0037 第三节与 ADR-0044。
 *
 * <p>三条硬规则：
 *
 * <ol>
 *   <li><b>券模板只能由平台创建</b>：统一券池的核心卖点，放开自建等于没有券池；
 *   <li><b>编码与成本归属创建后不可改</b>：编码挂在券实例与适用范围上；成本归属一旦有人
 *       按它承诺过额度，改了就改了考核的归属。改这两项一律 40001，不静默忽略；
 *   <li><b>改面额 / 门槛 / 有效期只影响之后新发的券</b>：已发出去的券存的是发放时的快照，
 *       它是平台对用户的承诺（与目录项「现取不存快照」正好相反，两者都是刻意的）。
 * </ol>
 *
 * <p>券池总览同时是**对账**：{@code issued = redeemed + reserved + expired}，
 * 不平即告警。本项目没有资金可对（ADR-0036），这张就是券的对账。
 */
@Service
public class CouponPoolService {

    private final CouponTemplateMapper templateMapper;
    private final CouponContributionMapper contributionMapper;
    private final CouponMapper couponMapper;
    private final CatalogQueryApi catalog;
    private final ProviderConsole providerConsole;
    private final CouponService couponService;

    public CouponPoolService(CouponTemplateMapper templateMapper,
                             CouponContributionMapper contributionMapper,
                             CouponMapper couponMapper,
                             CatalogQueryApi catalog,
                             ProviderConsole providerConsole,
                             CouponService couponService) {
        this.templateMapper = templateMapper;
        this.contributionMapper = contributionMapper;
        this.couponMapper = couponMapper;
        this.catalog = catalog;
        this.providerConsole = providerConsole;
        this.couponService = couponService;
    }

    // ---------------------------------------------------------------- 运营侧：模板

    /** 券模板分页（含停用——运营要看得见自己停用过的）。 */
    @Transactional(readOnly = true)
    public PageResult<CouponDtos.CouponTemplateView> listTemplates(Integer status, Integer costBearer,
                                                                   String keyword, long page, long pageSize) {
        CurrentUser.requireAdmin();
        String trimmed = keyword == null ? null : keyword.trim();
        Page<CouponTemplate> result = templateMapper.selectPage(new Page<>(page, pageSize),
                Wrappers.<CouponTemplate>lambdaQuery()
                        .eq(status != null, CouponTemplate::getStatus, status)
                        .eq(costBearer != null, CouponTemplate::getCostBearer, costBearer)
                        .like(trimmed != null && !trimmed.isEmpty(), CouponTemplate::getName, trimmed)
                        .orderByDesc(CouponTemplate::getUpdatedAt)
                        .orderByDesc(CouponTemplate::getId));
        Map<Long, Integer> issued = issuedCounts(result.getRecords());
        return PageResult.from(result, template -> PrivilegeViews.toTemplateView(
                template, issued.getOrDefault(template.getId(), 0), catalog));
    }

    /** 新建券模板。 */
    @Transactional
    public CouponDtos.CouponTemplateView createTemplate(CouponDtos.CouponTemplateRequest request) {
        CurrentUser.requireAdmin();
        CouponTemplate existing = templateMapper.selectOne(Wrappers.<CouponTemplate>lambdaQuery()
                .eq(CouponTemplate::getCode, request.code()));
        if (existing != null) {
            throw BusinessException.conflict("券模板编码已存在（编码不可复用）");
        }
        CouponTemplate template = new CouponTemplate();
        template.setCode(request.code());
        applyRequest(template, request, true);
        templateMapper.insert(template);
        return PrivilegeViews.toTemplateView(template, 0, catalog);
    }

    /** 修改券模板（编码与成本归属不可改）。 */
    @Transactional
    public CouponDtos.CouponTemplateView updateTemplate(long templateId,
                                                        CouponDtos.CouponTemplateRequest request) {
        CurrentUser.requireAdmin();
        CouponTemplate template = requireTemplate(templateId);
        if (!template.getCode().equals(request.code())) {
            throw BusinessException.paramInvalid("模板编码不可变更（券实例与适用范围都引用它）");
        }
        if (!template.getCostBearer().equals(request.costBearer())) {
            throw BusinessException.paramInvalid("成本归属不可变更（已有服务者按它承诺过额度）");
        }
        applyRequest(template, request, false);
        templateMapper.updateById(template);
        return PrivilegeViews.toTemplateView(template, couponMapper.countIssuedByTemplate(templateId), catalog);
    }

    /** 上架 / 下架：停用只挡新的发放与新的贡献，已发出的券照常可核销。 */
    @Transactional
    public CouponDtos.CouponTemplateView changeTemplateStatus(long templateId,
                                                              CouponDtos.CouponTemplateStatusRequest request) {
        CurrentUser.requireAdmin();
        CouponTemplate template = requireTemplate(templateId);
        template.setStatus(request.status());
        templateMapper.updateById(template);
        return PrivilegeViews.toTemplateView(template, couponMapper.countIssuedByTemplate(templateId), catalog);
    }

    /** 券池总览 + 对账。 */
    @Transactional(readOnly = true)
    public CouponDtos.CouponPoolOverviewView overview() {
        CurrentUser.requireAdmin();
        LocalDateTime now = AppTime.now();

        List<CouponTemplate> templates = templateMapper.selectList(Wrappers.<CouponTemplate>lambdaQuery());
        int activeTemplates = (int) templates.stream().filter(CouponTemplate::isEnabled).count();
        int providerCost = (int) templates.stream()
                .filter(t -> t.getCostBearer() != null && t.getCostBearer() == CouponTemplate.COST_PROVIDER).count();
        int platformSubsidy = (int) templates.stream()
                .filter(CouponTemplate::isPlatformSubsidy).count();

        List<CouponContribution> contributions = contributionMapper.selectList(
                Wrappers.<CouponContribution>lambdaQuery().eq(CouponContribution::getStatus,
                        CouponContribution.STATUS_ACTIVE));
        // 承诺额度合计与「还可发放」都要按每条贡献的实时账算——不是把承诺数加起来就完事，
        // 那样会把已经发出去的部分也算成「还能发」。
        int committed = 0;
        int available = 0;
        for (CouponContribution contribution : contributions) {
            committed += contribution.getTotalCount() == null ? 0 : contribution.getTotalCount();
            CouponQuota quota = CouponQuota.of(contribution,
                    couponMapper.statOfContribution(contribution.getId(), now));
            available += quota.available();
        }

        List<CouponStat> bySource = couponMapper.statsBySource(now);
        int issued = 0;
        int redeemed = 0;
        int reserved = 0;
        int expired = 0;
        List<CouponDtos.CouponSourceStatView> sourceStats = new ArrayList<>();
        for (CouponStat stat : bySource) {
            issued += n(stat.getIssued());
            redeemed += n(stat.getRedeemed());
            reserved += n(stat.getReserved());
            expired += n(stat.getExpired());
            sourceStats.add(new CouponDtos.CouponSourceStatView(stat.getSource(), n(stat.getIssued()),
                    n(stat.getRedeemed()), n(stat.getReserved()), n(stat.getExpired())));
        }

        boolean balanced = issued == redeemed + reserved + expired;
        CouponDtos.CouponReconciliationView reconciliation = new CouponDtos.CouponReconciliationView(
                issued, redeemed, reserved, expired, balanced,
                "实例数 = 已发放 = 已核销 + 未过期未核销 + 已过期未核销（ADR-0037 第三节）");
        return new CouponDtos.CouponPoolOverviewView(
                templates.size(), activeTemplates, providerCost, platformSubsidy,
                contributions.size(), committed, available,
                issued, redeemed, reserved, expired, sourceStats, reconciliation);
    }

    /** 券实例分页（运营查询与排查）。 */
    @Transactional(readOnly = true)
    public PageResult<CouponDtos.CouponView> listCoupons(Long templateId, Integer source, Integer status,
                                                         Long userId, Long providerId,
                                                         long page, long pageSize) {
        CurrentUser.requireAdmin();
        Page<Coupon> result = couponMapper.selectPage(new Page<>(page, pageSize),
                Wrappers.<Coupon>lambdaQuery()
                        .eq(templateId != null, Coupon::getTemplateId, templateId)
                        .eq(source != null, Coupon::getSource, source)
                        .eq(status != null, Coupon::getStatus, status)
                        .eq(userId != null, Coupon::getUserId, userId)
                        .eq(providerId != null, Coupon::getProviderId, providerId)
                        .orderByDesc(Coupon::getIssuedAt)
                        .orderByDesc(Coupon::getId));
        Map<Long, CouponTemplate> templates = templatesOf(result.getRecords());
        Map<Long, String> providerNames = providerNamesOf(result.getRecords());
        return PageResult.from(result, coupon -> PrivilegeViews.toCouponView(coupon,
                templates.get(coupon.getTemplateId()),
                coupon.getProviderId() == null ? null : providerNames.get(coupon.getProviderId())));
    }

    /**
     * 运营定向发放平台补贴券（「平台补贴」这一来源的真实产出路径）。
     *
     * <p>只发平台补贴券：服务者成本的券走服务者的贡献额度，平台不能替服务者放券。
     * 回读模板是为了把模板编码 / 名称一起给运营看（列表页上只有 id 没法用）。
     */
    @Transactional
    public CouponDtos.CouponView issueSubsidyCoupon(long userId, long templateId, String remark) {
        CurrentUser.requireAdmin();
        CouponApi.CouponInfo info = couponService.issueSubsidyCoupon(userId, templateId, remark);
        CouponTemplate template = templateMapper.selectById(info.templateId());
        Coupon coupon = couponMapper.selectById(info.id());
        return PrivilegeViews.toCouponView(coupon, template,
                coupon.getProviderId() == null ? null
                        : providerConsole.namesOf(List.of(coupon.getProviderId())).get(coupon.getProviderId()));
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 把请求写进实体（新建与修改共用）。
     *
     * <p>适用范围校验放在写入口：编码写错了要到核销时才会暴露，而那时券已经发到用户手里了。
     */
    private void applyRequest(CouponTemplate template, CouponDtos.CouponTemplateRequest request, boolean creating) {
        template.setName(request.name().trim());
        template.setFaceValue(Price.parsePositive(request.faceValue(), "券面额"));
        template.setMinAmount(request.minAmount() == null || request.minAmount().isBlank()
                ? BigDecimal.ZERO
                : Price.parse(request.minAmount(), "使用门槛"));
        template.setValidDays(request.validDays());
        template.setCostBearer(request.costBearer());
        template.setScopeType(request.scopeType() == null ? CouponTemplate.SCOPE_NONE : request.scopeType());
        template.setScopeCodes(String.join(",", validateScope(template.getScopeType(), request.scopeCodes())));
        // 发放上限只对平台补贴券有意义：服务者成本券的额度由贡献决定，
        // 两套额度混在一起会让「还能发多少」有两个答案。
        template.setIssueLimit(template.isPlatformSubsidy() ? request.issueLimit() : null);
        template.setDescription(request.description() == null || request.description().isBlank()
                ? null : request.description().trim());
        if (creating) {
            template.setStatus(CouponTemplate.STATUS_ENABLED);
        }
    }

    /**
     * 校验适用范围编码在标准目录里存在（ADR-0037：适用范围与标准目录编码挂钩）。
     *
     * @return 去重后的编码（顺序保留）
     */
    private List<String> validateScope(int scopeType, List<String> scopeCodes) {
        List<String> codes = scopeCodes == null ? List.of() : scopeCodes.stream()
                .map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        if (scopeType == CouponTemplate.SCOPE_NONE) {
            if (!codes.isEmpty()) {
                throw BusinessException.paramInvalid("不限适用范围时不要传适用范围编码");
            }
            return List.of();
        }
        if (codes.isEmpty()) {
            throw BusinessException.paramInvalid("限定适用范围时必须给出编码（分类编码或目录项编码）");
        }
        Set<String> missing = new LinkedHashSet<>();
        if (scopeType == CouponTemplate.SCOPE_CATEGORY) {
            Map<String, String> names = catalog.categoryNames(codes);
            codes.stream().filter(code -> !names.containsKey(code)).forEach(missing::add);
        } else {
            Map<String, CatalogQueryApi.ItemInfo> items = catalog.findItems(codes);
            codes.stream().filter(code -> !items.containsKey(code)).forEach(missing::add);
        }
        if (!missing.isEmpty()) {
            throw BusinessException.paramInvalid("适用范围在标准目录里不存在：" + String.join("、", missing));
        }
        return codes;
    }

    private CouponTemplate requireTemplate(long templateId) {
        CouponTemplate template = templateMapper.selectById(templateId);
        if (template == null) {
            throw BusinessException.notFound("券模板不存在");
        }
        return template;
    }

    /** 一批模板各自的已发放张数（一次分组查询，不是每行一次）。 */
    Map<Long, Integer> issuedCounts(List<CouponTemplate> templates) {
        if (templates.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = templates.stream().map(CouponTemplate::getId).toList();
        Map<Long, Integer> counts = new HashMap<>();
        for (Map<String, Object> row : templateMapper.countIssuedByTemplates(ids)) {
            counts.put(((Number) row.get("template_id")).longValue(),
                    ((Number) row.get("issued")).intValue());
        }
        return counts;
    }

    Map<Long, CouponTemplate> templatesOf(List<Coupon> coupons) {
        Set<Long> ids = new LinkedHashSet<>();
        coupons.forEach(coupon -> ids.add(coupon.getTemplateId()));
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, CouponTemplate> templates = new HashMap<>();
        for (CouponTemplate template : templateMapper.selectBatchIds(ids)) {
            templates.put(template.getId(), template);
        }
        return templates;
    }

    private Map<Long, String> providerNamesOf(List<Coupon> coupons) {
        Set<Long> ids = new LinkedHashSet<>();
        coupons.forEach(coupon -> {
            if (coupon.getProviderId() != null) {
                ids.add(coupon.getProviderId());
            }
        });
        // 门店名经 ph-provider 的 api 取（ADR-0006）；没接线时只是少一列，不该让整页失败
        return providerConsole.namesOf(ids);
    }

    private static int n(Integer value) {
        return value == null ? 0 : value;
    }
}
