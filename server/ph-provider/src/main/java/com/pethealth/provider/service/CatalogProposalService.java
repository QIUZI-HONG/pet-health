package com.pethealth.provider.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pethealth.api.admin.CatalogItemProposalApproveRequest;
import com.pethealth.api.admin.ReviewRejectRequest;
import com.pethealth.api.catalog.CatalogItemProposalRequest;
import com.pethealth.api.catalog.CatalogItemProposalSummary;
import com.pethealth.api.catalog.CatalogItemProposalView;
import com.pethealth.catalog.api.CatalogItemApi;
import com.pethealth.catalog.api.CatalogQueryApi;
import com.pethealth.catalog.api.Price;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.time.AppTime;
import com.pethealth.common.util.Text;
import com.pethealth.provider.domain.CatalogItemProposal;
import com.pethealth.provider.domain.Provider;
import com.pethealth.provider.domain.ProviderReviewLog;
import com.pethealth.provider.mapper.CatalogItemProposalMapper;
import com.pethealth.provider.mapper.ProviderMapper;
import com.pethealth.provider.mapper.ProviderReviewLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 目录外服务提案（交付文档 F012 / 2.5：目录外服务须经平台审核），决策见 ADR-0034。
 *
 * <p>服务者**不能自由建项**：目录是平台资产（F010），服务者只能提案；审核通过后由
 * ph-catalog 建正式项目并回传编码，服务者随后才能在自己店里定价上架。
 *
 * <p>一条刻意的边界：**最终区间由运营在审核通过时给出**，不默认采纳服务者的建议区间。
 * 建议区间是「他认为的市场价」，而区间是平台的规则——让申请方定义规则，等于让区间校验自己批自己。
 *
 * <p>提案与目录之间只有一次创建关系：通过之后这条目录项就独立存在，平台可以再改它，
 * 提案不跟随（记住的只是「当初是哪次提案产生了它」）。
 */
@Service
public class CatalogProposalService {

    private final CatalogItemProposalMapper proposalMapper;
    private final ProviderMapper providerMapper;
    private final ProviderReviewLogMapper reviewLogMapper;
    private final ProviderAccess access;
    private final ReviewLogRecorder reviewLog;
    private final CatalogItemApi catalogItemApi;
    private final CatalogQueryApi catalogQueryApi;

    public CatalogProposalService(CatalogItemProposalMapper proposalMapper, ProviderMapper providerMapper,
                                  ProviderReviewLogMapper reviewLogMapper, ProviderAccess access,
                                  ReviewLogRecorder reviewLog, CatalogItemApi catalogItemApi,
                                  CatalogQueryApi catalogQueryApi) {
        this.proposalMapper = proposalMapper;
        this.providerMapper = providerMapper;
        this.reviewLogMapper = reviewLogMapper;
        this.access = access;
        this.reviewLog = reviewLog;
        this.catalogItemApi = catalogItemApi;
        this.catalogQueryApi = catalogQueryApi;
    }

    // ---------------------------------------------------------------- 服务者侧

    /** 提交提案。分类必须存在且启用——提一个不存在的分类只会在审核时被退回。 */
    @Transactional
    public CatalogItemProposalView submit(CatalogItemProposalRequest request) {
        long userId = access.currentUserId();
        Provider provider = access.requireActiveAdmin(userId);

        String categoryCode = request.categoryCode().trim();
        if (!catalogQueryApi.categoryNames(Set.of(categoryCode)).containsKey(categoryCode)) {
            throw BusinessException.paramInvalid("分类 " + categoryCode + " 不存在或已停用");
        }
        BigDecimal min = Price.parse(request.priceMin(), "建议价格区间下限");
        BigDecimal max = Price.parse(request.priceMax(), "建议价格区间上限");
        if (min.compareTo(max) > 0) {
            throw BusinessException.paramInvalid("建议价格区间下限不能高于上限");
        }

        CatalogItemProposal proposal = new CatalogItemProposal();
        proposal.setProviderId(provider.getId());
        proposal.setCategoryCode(categoryCode);
        proposal.setName(request.name().trim());
        proposal.setDescription(Text.trimToNull(request.description()));
        proposal.setSuggestedPriceMin(min);
        proposal.setSuggestedPriceMax(max);
        proposal.setSuggestedPriceUnit(Text.trimToNull(request.priceUnit()) == null
                ? "次"
                : request.priceUnit().trim());
        proposal.setStatus(CatalogItemProposal.STATUS_PENDING);
        proposal.setSubmittedAt(AppTime.now());
        proposalMapper.insert(proposal);

        reviewLog.record(ProviderReviewLog.TARGET_PROPOSAL, proposal.getId(), provider.getId(),
                ProviderReviewLog.ACTION_SUBMIT, null);
        return detail(proposal);
    }

    @Transactional(readOnly = true)
    public PageResult<CatalogItemProposalSummary> listMine(Integer status, long page, long pageSize) {
        long userId = access.currentUserId();
        Provider provider = access.requireBound(userId);
        Page<CatalogItemProposal> result = proposalMapper.selectPage(new Page<>(page, pageSize),
                Wrappers.<CatalogItemProposal>lambdaQuery()
                        .eq(CatalogItemProposal::getProviderId, provider.getId())
                        .eq(status != null, CatalogItemProposal::getStatus, status)
                        .orderByDesc(CatalogItemProposal::getSubmittedAt)
                        .orderByDesc(CatalogItemProposal::getId));
        Map<String, String> categoryNames = categoryNamesOf(result.getRecords());
        return PageResult.from(result, proposal -> ProviderViews.toProposalSummary(
                proposal, provider, categoryNames.get(proposal.getCategoryCode())));
    }

    @Transactional(readOnly = true)
    public CatalogItemProposalView getMine(long proposalId) {
        long userId = access.currentUserId();
        Provider provider = access.requireBound(userId);
        return detail(requireOwned(proposalId, provider.getId()));
    }

    // ---------------------------------------------------------------- 运营侧

    /** 提案审核队列：默认只看待审核。 */
    @Transactional(readOnly = true)
    public PageResult<CatalogItemProposalSummary> listForReview(Integer status, long page, long pageSize) {
        CurrentUser.requireAdmin();
        Page<CatalogItemProposal> result = proposalMapper.selectPage(new Page<>(page, pageSize),
                Wrappers.<CatalogItemProposal>lambdaQuery()
                        .eq(status != null, CatalogItemProposal::getStatus, status)
                        .orderByAsc(CatalogItemProposal::getSubmittedAt)
                        .orderByAsc(CatalogItemProposal::getId));
        Map<Long, Provider> providers = providersOf(result.getRecords());
        Map<String, String> categoryNames = categoryNamesOf(result.getRecords());
        return PageResult.from(result, proposal -> ProviderViews.toProposalSummary(
                proposal, providers.get(proposal.getProviderId()),
                categoryNames.get(proposal.getCategoryCode())));
    }

    @Transactional(readOnly = true)
    public CatalogItemProposalView getForReview(long proposalId) {
        CurrentUser.requireAdmin();
        return detail(requireProposal(proposalId));
    }

    /**
     * 审核通过：**由运营给出最终区间**（必填），平台据此建正式目录项，编码回写到提案单。
     *
     * <p>编码可以不传：不传就按分类前缀排下一个序号（{@code HE-014}）。传了就必须与分类前缀一致
     * 且没被占用——编码不可复用，重号只能报错。
     */
    @Transactional
    public CatalogItemProposalView approve(long proposalId, CatalogItemProposalApproveRequest request) {
        long reviewerId = CurrentUser.requireAdmin();
        CatalogItemProposal proposal = requireProposal(proposalId);
        requirePending(proposal);

        BigDecimal min = Price.parse(request.priceMin(), "最终价格区间下限");
        BigDecimal max = Price.parse(request.priceMax(), "最终价格区间上限");
        if (min.compareTo(max) > 0) {
            throw BusinessException.paramInvalid("价格区间下限不能高于上限");
        }
        CatalogItemApi.Draft draft = new CatalogItemApi.Draft(
                proposal.getCategoryCode(),
                Text.trimToNull(request.name()) == null ? proposal.getName() : request.name().trim(),
                proposal.getDescription(),
                min,
                max,
                Text.trimToNull(request.priceUnit()) == null
                        ? proposal.getSuggestedPriceUnit()
                        : request.priceUnit().trim(),
                Text.trimToNull(request.code()));

        CatalogQueryApi.ItemInfo created = catalogItemApi.createFromProposal(draft);

        proposal.setStatus(CatalogItemProposal.STATUS_APPROVED);
        proposal.setItemCode(created.code());
        proposal.setReviewedAt(AppTime.now());
        proposal.setReviewerId(reviewerId);
        proposal.setReviewRemark(Text.trimToNull(request.remark()));
        proposalMapper.updateById(proposal);

        reviewLog.record(ProviderReviewLog.TARGET_PROPOSAL, proposal.getId(), proposal.getProviderId(),
                ProviderReviewLog.ACTION_APPROVE, proposal.getReviewRemark());
        return detail(proposal);
    }

    @Transactional
    public CatalogItemProposalView reject(long proposalId, ReviewRejectRequest request) {
        long reviewerId = CurrentUser.requireAdmin();
        CatalogItemProposal proposal = requireProposal(proposalId);
        requirePending(proposal);
        String reason = request.reason().trim();

        proposal.setStatus(CatalogItemProposal.STATUS_REJECTED);
        proposal.setRejectReason(reason);
        proposal.setReviewedAt(AppTime.now());
        proposal.setReviewerId(reviewerId);
        proposalMapper.updateById(proposal);

        reviewLog.record(ProviderReviewLog.TARGET_PROPOSAL, proposal.getId(), proposal.getProviderId(),
                ProviderReviewLog.ACTION_REJECT, reason);
        return detail(proposal);
    }

    // ---------------------------------------------------------------- 内部

    private CatalogItemProposal requireOwned(long proposalId, long providerId) {
        CatalogItemProposal proposal = proposalMapper.selectOne(Wrappers.<CatalogItemProposal>lambdaQuery()
                .eq(CatalogItemProposal::getId, proposalId)
                .eq(CatalogItemProposal::getProviderId, providerId));
        if (proposal == null) {
            throw BusinessException.notFound();
        }
        return proposal;
    }

    private CatalogItemProposal requireProposal(long proposalId) {
        CatalogItemProposal proposal = proposalMapper.selectById(proposalId);
        if (proposal == null) {
            throw BusinessException.notFound();
        }
        return proposal;
    }

    private void requirePending(CatalogItemProposal proposal) {
        if (!proposal.isPending()) {
            throw BusinessException.conflict("该提案不在待审核状态（可能已被处理过）");
        }
    }

    private CatalogItemProposalView detail(CatalogItemProposal proposal) {
        Provider provider = providerMapper.selectById(proposal.getProviderId());
        String categoryName = categoryNamesOf(List.of(proposal)).get(proposal.getCategoryCode());
        return ProviderViews.toProposalView(proposal, provider, categoryName, logsOf(proposal.getId()));
    }

    /** 提案的审核流水（append-only，按时间正序：先提交、后审核）。 */
    private List<ProviderReviewLog> logsOf(long proposalId) {
        return reviewLogMapper.selectList(Wrappers.<ProviderReviewLog>lambdaQuery()
                .eq(ProviderReviewLog::getTargetType, ProviderReviewLog.TARGET_PROPOSAL)
                .eq(ProviderReviewLog::getTargetId, proposalId)
                .orderByAsc(ProviderReviewLog::getId));
    }

    private Map<Long, Provider> providersOf(List<CatalogItemProposal> proposals) {
        Set<Long> ids = proposals.stream().map(CatalogItemProposal::getProviderId).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return providerMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Provider::getId, provider -> provider, (first, second) -> first));
    }

    private Map<String, String> categoryNamesOf(List<CatalogItemProposal> proposals) {
        Set<String> codes = proposals.stream().map(CatalogItemProposal::getCategoryCode)
                .collect(Collectors.toSet());
        return catalogQueryApi.categoryNames(codes);
    }
}
