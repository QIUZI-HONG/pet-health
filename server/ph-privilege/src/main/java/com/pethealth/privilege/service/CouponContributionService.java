package com.pethealth.privilege.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pethealth.api.privilege.CouponDtos;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.error.ErrorCode;
import com.pethealth.common.time.AppTime;
import com.pethealth.privilege.domain.Coupon;
import com.pethealth.privilege.domain.CouponContribution;
import com.pethealth.privilege.domain.CouponContributionLog;
import com.pethealth.privilege.domain.CouponTemplate;
import com.pethealth.privilege.mapper.CouponContributionLogMapper;
import com.pethealth.privilege.mapper.CouponContributionMapper;
import com.pethealth.privilege.mapper.CouponMapper;
import com.pethealth.privilege.mapper.CouponTemplateMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务者的券贡献（切片 #110 的服务者侧），决策见 ADR-0037 第三节与 ADR-0044。
 *
 * <p>四个动作，都在**一个服务者的一条额度账**上做：
 *
 * <ul>
 *   <li>选券并承诺额度（{@code total_count}）；
 *   <li>调整额度（或重新启用已停止的那条）；
 *   <li>撤回未发放的额度（把余量收回，已发出的券不受影响）；
 *   <li>看自己的贡献与核销统计（含额度流水）。
 * </ul>
 *
 * <p><b>调不下去的部分不能收回</b>：{@code total_count} 必须 ≥ 已核销 + 占用中。
 * 「占用中」是已经发到用户手里的券——服务者反悔不能让用户的券失效，
 * 平台已经答应过用户的事不能再改（ADR-0037 的过期回退与之一致：只有**未发放**的才回池）。
 *
 * <p><b>越权一律 40400</b>：所有查询与更新都带 {@code provider_id} 条件，
 * 「别人的贡献」与「不存在的贡献」对外是同一个结果（docs/conventions.md）。
 */
@Service
public class CouponContributionService {

    private final CouponContributionMapper contributionMapper;
    private final CouponContributionLogMapper logMapper;
    private final CouponTemplateMapper templateMapper;
    private final CouponMapper couponMapper;
    private final ProviderConsole providerConsole;
    private final com.pethealth.catalog.api.CatalogQueryApi catalog;

    public CouponContributionService(CouponContributionMapper contributionMapper,
                                     CouponContributionLogMapper logMapper,
                                     CouponTemplateMapper templateMapper,
                                     CouponMapper couponMapper,
                                     ProviderConsole providerConsole,
                                     com.pethealth.catalog.api.CatalogQueryApi catalog) {
        this.contributionMapper = contributionMapper;
        this.logMapper = logMapper;
        this.templateMapper = templateMapper;
        this.couponMapper = couponMapper;
        this.providerConsole = providerConsole;
        this.catalog = catalog;
    }

    // ---------------------------------------------------------------- 服务者侧

    /** 券池里可贡献的模板：只有**服务者成本**且启用中的（平台补贴券不让服务者承诺）。 */
    @Transactional(readOnly = true)
    public PageResult<CouponDtos.CouponTemplateView> listContributableTemplates(String keyword,
                                                                                long page, long pageSize) {
        requireProviderAdmin();
        String trimmed = keyword == null ? null : keyword.trim();
        Page<CouponTemplate> result = templateMapper.selectPage(new Page<>(page, pageSize),
                Wrappers.<CouponTemplate>lambdaQuery()
                        .eq(CouponTemplate::getStatus, CouponTemplate.STATUS_ENABLED)
                        .eq(CouponTemplate::getCostBearer, CouponTemplate.COST_PROVIDER)
                        .like(trimmed != null && !trimmed.isEmpty(), CouponTemplate::getName, trimmed)
                        .orderByDesc(CouponTemplate::getUpdatedAt)
                        .orderByDesc(CouponTemplate::getId));
        Map<Long, Integer> issued = new HashMap<>();
        for (Map<String, Object> row : templateMapper.countIssuedByTemplates(
                result.getRecords().stream().map(CouponTemplate::getId).toList())) {
            issued.put(((Number) row.get("template_id")).longValue(), ((Number) row.get("issued")).intValue());
        }
        return PageResult.from(result, template -> PrivilegeViews.toTemplateView(
                template, issued.getOrDefault(template.getId(), 0), catalog));
    }

    /** 我的券贡献（含额度账）。 */
    @Transactional(readOnly = true)
    public PageResult<CouponDtos.CouponContributionView> listMine(Integer status, long page, long pageSize) {
        long providerId = requireProviderAdmin();
        Page<CouponContribution> result = contributionMapper.selectPage(new Page<>(page, pageSize),
                Wrappers.<CouponContribution>lambdaQuery()
                        .eq(CouponContribution::getProviderId, providerId)
                        .eq(status != null, CouponContribution::getStatus, status)
                        .orderByDesc(CouponContribution::getUpdatedAt)
                        .orderByDesc(CouponContribution::getId));
        return PageResult.from(result, contribution -> view(contribution, null, false));
    }

    /** 贡献详情（多额度流水）。 */
    @Transactional(readOnly = true)
    public CouponDtos.CouponContributionView detail(long contributionId) {
        long providerId = requireProviderAdmin();
        CouponContribution contribution = requireMine(contributionId, providerId);
        return view(contribution, requireTemplate(contribution.getTemplateId()), true);
    }

    /**
     * 选券并承诺额度。
     *
     * <p>已有记录（含已停止发放的那条）→ 40900：**一个服务者对一个模板只留一条额度账**，
     * 调额度用 {@code PUT}、重新启用用 {@code PUT} 把 status 写回 1。
     * 新建第二条会让「这家店在这个券上承诺了多少」有两个答案。
     */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public CouponDtos.CouponContributionView commit(CouponDtos.CouponContributionRequest request) {
        long providerId = requireProviderAdmin();
        CouponTemplate template = requireContributable(request.templateId());

        CouponContribution existing = contributionMapper.selectOne(Wrappers.<CouponContribution>lambdaQuery()
                .eq(CouponContribution::getProviderId, providerId)
                .eq(CouponContribution::getTemplateId, template.getId()));
        if (existing != null) {
            throw BusinessException.conflict("该券模板已有贡献记录；调整额度或重新启用请用改额度接口");
        }

        CouponContribution contribution = new CouponContribution();
        contribution.setProviderId(providerId);
        contribution.setTemplateId(template.getId());
        contribution.setTotalCount(request.totalCount());
        contribution.setStatus(CouponContribution.STATUS_ACTIVE);
        contribution.setRemark(remark(request.remark()));
        contributionMapper.insert(contribution);

        record(contribution, CouponContributionLog.ACTION_COMMIT, request.remark());
        return view(contribution, template, false);
    }

    /**
     * 调整额度（不传 status 表示只改额度；传 1 重新启用、传 2 等于撤回）。
     *
     * <p>下限是「已核销 + 占用中」：已经发到用户手里的券收不回来（ADR-0044 的口径）。
     */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public CouponDtos.CouponContributionView update(long contributionId,
                                                    CouponDtos.CouponContributionRequest request) {
        long providerId = requireProviderAdmin();
        // 锁住这一行再算下限：不锁的话「两个请求同时调低额度」可能各自都通过校验，
        // 合起来把 total 压到已发出的数量之下——那正是「用户的券被无效化」的路径。
        // 隔离级别取 READ COMMITTED：等锁之后要读到的是别人**刚提交**的那些券（同 CouponService.issue）。
        CouponContribution contribution = contributionMapper.selectForUpdate(contributionId);
        if (contribution == null || !contribution.getProviderId().equals(providerId)) {
            throw BusinessException.notFound();
        }
        CouponTemplate template = requireTemplate(contribution.getTemplateId());

        CouponQuota quota = quotaOf(contribution);
        if (request.totalCount() < quota.committed()) {
            throw BusinessException.paramInvalid("承诺额度不能低于已核销加占用中的 " + quota.committed()
                    + " 张（已经发到用户手里的券收不回来）");
        }
        contribution.setTotalCount(request.totalCount());
        if (request.status() != null) {
            contribution.setStatus(request.status());
        }
        contribution.setRemark(remark(request.remark()));
        contributionMapper.updateById(contribution);

        record(contribution, request.status() != null && request.status() == CouponContribution.STATUS_STOPPED
                ? CouponContributionLog.ACTION_WITHDRAW : CouponContributionLog.ACTION_ADJUST,
                request.remark());
        return view(contribution, template, false);
    }

    /**
     * 撤回未发放的额度：{@code total_count} 夹到「已核销 + 占用中」，可发放归零。
     *
     * <p>幂等（重复撤回结果相同，不报 404）：撤回的目标是自己的那条记录，
     * 重复点两下不该报错——这与「删别人的资源报 404」是两回事，
     * 越权的判断在 {@code requireMine} 里做（别人的贡献仍然 404）。
     */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public CouponDtos.CouponContributionView withdraw(long contributionId) {
        long providerId = requireProviderAdmin();
        CouponContribution contribution = contributionMapper.selectForUpdate(contributionId);
        if (contribution == null || !contribution.getProviderId().equals(providerId)) {
            throw BusinessException.notFound();
        }
        CouponTemplate template = requireTemplate(contribution.getTemplateId());
        CouponQuota quota = quotaOf(contribution);

        int floor = quota.committed();
        // 目标态：额度夹到下限、状态转为「已停止发放」。只有真的改变了目标态才写流水——
        // 重复撤回是幂等的，不该在额度流水里留下第二条一模一样的记录（那是噪音，也会让
        // 「这份额度账被改过几次」数不准）
        boolean changed = !Integer.valueOf(floor).equals(contribution.getTotalCount())
                || !Integer.valueOf(CouponContribution.STATUS_STOPPED).equals(contribution.getStatus());
        if (changed) {
            contribution.setTotalCount(floor);
            contribution.setStatus(CouponContribution.STATUS_STOPPED);
            contribution.setRemark("撤回未发放的额度");
            contributionMapper.updateById(contribution);
            record(contribution, CouponContributionLog.ACTION_WITHDRAW, "撤回未发放的额度");
        }
        return view(contribution, template, false);
    }

    /** 本店贡献券的发放与核销明细（服务者的「对账视图」：没有余额与提现，只有流水与明细）。 */
    @Transactional(readOnly = true)
    public PageResult<CouponDtos.CouponView> listCoupons(long contributionId, Integer status,
                                                         long page, long pageSize) {
        long providerId = requireProviderAdmin();
        CouponContribution contribution = requireMine(contributionId, providerId);
        CouponTemplate template = requireTemplate(contribution.getTemplateId());
        Page<Coupon> result = couponMapper.selectPage(new Page<>(page, pageSize),
                Wrappers.<Coupon>lambdaQuery()
                        .eq(Coupon::getContributionId, contribution.getId())
                        .eq(status != null, Coupon::getStatus, status)
                        .orderByDesc(Coupon::getIssuedAt)
                        .orderByDesc(Coupon::getId));
        // 服务者看自己的券：不带领券人身份，也不需要自己的门店名
        return PageResult.from(result, coupon -> PrivilegeViews.toCouponView(coupon, template, null));
    }

    // ---------------------------------------------------------------- 内部

    /** 额度账（口径在 {@link CouponQuota}）。 */
    CouponQuota quotaOf(CouponContribution contribution) {
        return CouponQuota.of(contribution,
                couponMapper.statOfContribution(contribution.getId(), AppTime.now()));
    }

    /** 记一条额度流水（append-only）。 */
    void record(CouponContribution contribution, int action, String remark) {
        CouponContributionLog log = new CouponContributionLog();
        log.setContributionId(contribution.getId());
        log.setProviderId(contribution.getProviderId());
        log.setAction(action);
        log.setTotalCount(contribution.getTotalCount());
        log.setAvailableCount(quotaOf(contribution).available());
        log.setRemark(remark(remark));
        logMapper.insert(log);
    }

    private CouponDtos.CouponContributionView view(CouponContribution contribution, CouponTemplate template,
                                                   boolean withLogs) {
        CouponQuota quota = quotaOf(contribution);
        List<CouponContributionLog> logs = withLogs
                ? logMapper.selectList(Wrappers.<CouponContributionLog>lambdaQuery()
                        .eq(CouponContributionLog::getContributionId, contribution.getId())
                        .orderByAsc(CouponContributionLog::getId))
                : null;
        String scopeDesc = template == null ? null : PrivilegeViews.scopeDesc(template, catalog);
        String providerName = providerConsole.namesOf(java.util.List.of(contribution.getProviderId()))
                .get(contribution.getProviderId());
        return PrivilegeViews.toContributionView(contribution, template, quota, providerName, scopeDesc, logs);
    }

    private long requireProviderAdmin() {
        return providerConsole.requireAdminProviderId();
    }

    private CouponContribution requireMine(long contributionId, long providerId) {
        CouponContribution contribution = contributionMapper.selectOne(
                Wrappers.<CouponContribution>lambdaQuery()
                        .eq(CouponContribution::getId, contributionId)
                        .eq(CouponContribution::getProviderId, providerId));
        if (contribution == null) {
            // 别人的贡献按不存在处理（404 不泄露「这个 id 存在」）
            throw BusinessException.notFound();
        }
        return contribution;
    }

    /** 可贡献的模板：存在、启用、且是服务者成本券（平台补贴券对服务者等于不存在）。 */
    private CouponTemplate requireContributable(long templateId) {
        CouponTemplate template = requireTemplate(templateId);
        if (!template.isEnabled()) {
            throw BusinessException.notFound("券模板不存在或已停用");
        }
        if (template.isPlatformSubsidy()) {
            throw BusinessException.notFound("券模板不存在或已停用");
        }
        return template;
    }

    private CouponTemplate requireTemplate(long templateId) {
        CouponTemplate template = templateMapper.selectById(templateId);
        if (template == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "券模板不存在");
        }
        return template;
    }

    private static String remark(String remark) {
        return remark == null || remark.isBlank() ? null : remark.trim();
    }
}
