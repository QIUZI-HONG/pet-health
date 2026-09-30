package com.pethealth.provider.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pethealth.api.admin.ReviewApproveRequest;
import com.pethealth.api.admin.ReviewRejectRequest;
import com.pethealth.api.provider.BusinessHour;
import com.pethealth.api.provider.ProviderServiceQueryApi;
import com.pethealth.api.provider.ProviderServiceRequest;
import com.pethealth.api.provider.ProviderServiceStatusRequest;
import com.pethealth.api.provider.ProviderServiceView;
import com.pethealth.api.provider.ServicePriceRequest;
import com.pethealth.catalog.api.CatalogPricingApi;
import com.pethealth.catalog.api.CatalogQueryApi;
import com.pethealth.catalog.api.Price;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.error.ErrorCode;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.time.AppTime;
import com.pethealth.provider.domain.BusinessHours;
import com.pethealth.provider.domain.Provider;
import com.pethealth.provider.domain.ProviderReviewLog;
import com.pethealth.provider.domain.ProviderServiceListing;
import com.pethealth.provider.mapper.ProviderMapper;
import com.pethealth.provider.mapper.ProviderServiceMapper;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 服务者选品定价与上架（切片 #105），决策见 ADR-0034。
 *
 * <p>状态机（与 {@link ProviderServiceListing} 的常量一一对应）：
 *
 * <pre>
 *   新增 / 改价 → 0 待审核 → 运营通过 → 1 已上架 → 服务者下架 → 2 已下架
 *                    ↘ 运营驳回 → 3 已驳回（改价后回到 0）
 *   2 已下架 →（价格未变，服务者上架）→ 1 已上架
 * </pre>
 *
 * <p>四条硬规则：
 *
 * <ol>
 *   <li><b>定价 100% 区间校验</b>（交付文档 2.5 的验收项）：校验点是
 *       {@link CatalogPricingApi#requirePriceInRange}——与将来的下单链路共用同一个实现，
 *       不在这里抄一遍区间比较；
 *   <li><b>未审核通过不许上架</b>：待审核与已驳回都上不了架，且回的是**能看懂的原因**
 *       （40300 + 「正在审核中」/「已被驳回」），不是笼统的无权限；
 *   <li><b>改价必须重审</b>：价格是对外承诺，改完直接生效等于绕过了平台的检查；
 *   <li><b>审核通过即上架</b>：服务者提交审核的意图就是上架（交付文档 7.2 的
 *       {@code merchant_service.status}（文档用词）默认值也是「上架」）；再上架不需要重审，
 *       因为价格没变——重审只是把审核员当橡皮图章。
 * </ol>
 *
 * <p>越权一律 40400：查询与更新都带 {@code provider_id} 条件，「别人的服务项」与「不存在的
 * 服务项」对外是同一个结果（docs/conventions.md）。
 *
 * <p>{@code @Primary} 是为了 {@link ProviderServiceQueryApi}：集成测试里有一个等价的桩
 * （{@code OrderTestSupport.StubCrossModuleApis}），取用方（{@code ProviderFacts}）用
 * {@code ObjectProvider} 就地取实现——两个候选且无主时会抛
 * {@code NoUniqueBeanDefinitionException}（与 {@link ProviderAccessAdapter} 同一处取舍）。
 */
@Service
@Primary
public class ServiceListingService implements ProviderServiceQueryApi {

    private final ProviderServiceMapper listingMapper;
    private final ProviderMapper providerMapper;
    private final ProviderAccess access;
    private final ReviewLogRecorder reviewLog;
    private final CatalogPricingApi pricingApi;
    private final CatalogQueryApi catalogApi;

    public ServiceListingService(ProviderServiceMapper listingMapper, ProviderMapper providerMapper,
                                 ProviderAccess access, ReviewLogRecorder reviewLog,
                                 CatalogPricingApi pricingApi, CatalogQueryApi catalogApi) {
        this.listingMapper = listingMapper;
        this.providerMapper = providerMapper;
        this.access = access;
        this.reviewLog = reviewLog;
        this.pricingApi = pricingApi;
        this.catalogApi = catalogApi;
    }

    // ------------------------------------------------- 跨模块只读出口（下单链路的前置）

    /**
     * 按 id 取本店**已上架**的服务项（{@link ProviderServiceQueryApi}）。
     *
     * <p>为什么归在本类：{@code provider_service} 是它的表，而「已上架」这个条件在本类里
     * 有唯一定义（{@link ProviderServiceListing#STATUS_LISTED}）。让下单链路自己去查这张表
     * 等于把状态机的含义复制出去一份（ADR-0006）。
     *
     * <p>「不存在 / 不是本店的 / 还没上架」三种都回空，由调用方统一按 40400 处理
     * （docs/conventions.md：不让调用方拿 id 探测别家门店的服务项）。
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<ProviderServiceView> findListedService(long providerId, long serviceId) {
        ProviderServiceListing listing = listingMapper.selectOne(Wrappers.<ProviderServiceListing>lambdaQuery()
                .eq(ProviderServiceListing::getId, serviceId)
                .eq(ProviderServiceListing::getProviderId, providerId)
                .eq(ProviderServiceListing::getStatus, ProviderServiceListing.STATUS_LISTED));
        return listing == null ? Optional.empty() : Optional.of(view(listing, null));
    }

    /**
     * 门店的营业时段（{@link ProviderServiceQueryApi}）。
     *
     * <p>读的是 {@code provider.business_hours} 那一列，解码走 {@link BusinessHours}
     * ——与 {@code ProviderProfileView} 里的是**同一个**解码口，不在这里再写一遍 JSON 解析
     * （脏数据的失败策略只该有一份：写失败必须抛、读失败记日志并按空处理）。
     *
     * <p>没配置营业时间时返回空集合，语义是**那天不可预约**而不是「全天可约」——
     * 后者会让一个还没填营业时间的门店被约爆。
     */
    @Override
    @Transactional(readOnly = true)
    public List<BusinessHour> businessHoursOf(long providerId) {
        Provider provider = providerMapper.selectById(providerId);
        return provider == null ? List.of() : BusinessHours.decode(provider.getBusinessHours());
    }

    // ---------------------------------------------------------------- 服务者侧

    /** 我的服务项列表（含待审核与已驳回——服务者要看得见自己的全部动作）。 */
    @Transactional(readOnly = true)
    public PageResult<ProviderServiceView> listMine(Integer status, long page, long pageSize) {
        long userId = access.currentUserId();
        Provider provider = access.requireBound(userId);
        Page<ProviderServiceListing> result = listingMapper.selectPage(new Page<>(page, pageSize),
                Wrappers.<ProviderServiceListing>lambdaQuery()
                        .eq(ProviderServiceListing::getProviderId, provider.getId())
                        .eq(status != null, ProviderServiceListing::getStatus, status)
                        .orderByDesc(ProviderServiceListing::getUpdatedAt)
                        .orderByDesc(ProviderServiceListing::getId));
        Map<String, CatalogQueryApi.ItemInfo> items = catalogItemsOf(result.getRecords());
        return PageResult.from(result, listing -> ProviderViews.toServiceView(
                listing, items.get(listing.getServiceCode()), null));
    }

    /** 从标准目录勾选服务项并定价：进待审核。 */
    @Transactional
    public ProviderServiceView create(ProviderServiceRequest request) {
        long userId = access.currentUserId();
        Provider provider = access.requireActiveAdmin(userId);
        // 资质过期后连新增选品也不允许：整店已经被自动下架，新增只会堆出更多不能上架的项
        access.requireValidQualification(provider.getId());

        String serviceCode = request.serviceCode().trim();
        BigDecimal price = Price.parsePositive(request.price(), "价格");
        pricingApi.requirePriceInRange(serviceCode, price);

        ProviderServiceListing existing = listingMapper.selectOne(Wrappers.<ProviderServiceListing>lambdaQuery()
                .eq(ProviderServiceListing::getProviderId, provider.getId())
                .eq(ProviderServiceListing::getServiceCode, serviceCode));
        if (existing != null) {
            // 不静默改价：一次误点的提交不该悄悄改掉线上价格，改价有独立接口
            throw BusinessException.conflict("该服务项已在你的服务列表里；调整价格请用改价接口");
        }

        ProviderServiceListing listing = new ProviderServiceListing();
        listing.setProviderId(provider.getId());
        listing.setServiceCode(serviceCode);
        listing.setPrice(price);
        listing.setStatus(ProviderServiceListing.STATUS_PENDING);
        listing.setSubmittedAt(AppTime.now());
        listingMapper.insert(listing);

        reviewLog.record(ProviderReviewLog.TARGET_LISTING, listing.getId(), provider.getId(),
                ProviderReviewLog.ACTION_SUBMIT, null);
        return view(listing, null);
    }

    /** 改价：回到待审核。已上架的服务项改价后立即撤下前台，直到再审通过。 */
    @Transactional
    public ProviderServiceView updatePrice(long listingId, ServicePriceRequest request) {
        long userId = access.currentUserId();
        Provider provider = access.requireActiveAdmin(userId);
        access.requireValidQualification(provider.getId());
        ProviderServiceListing listing = requireMine(listingId, provider.getId());

        BigDecimal price = Price.parsePositive(request.price(), "价格");
        pricingApi.requirePriceInRange(listing.getServiceCode(), price);

        listing.setPrice(price);
        listing.setStatus(ProviderServiceListing.STATUS_PENDING);
        listing.setRejectReason(null);
        listing.setSubmittedAt(AppTime.now());
        listing.setReviewedAt(null);
        listingMapper.updateById(listing);

        reviewLog.record(ProviderReviewLog.TARGET_LISTING, listing.getId(), provider.getId(),
                ProviderReviewLog.ACTION_RESUBMIT, null);
        return view(listing, null);
    }

    /** 上架 / 下架。上架要求「审核已通过（当前是已下架状态）」且资质未过期。 */
    @Transactional
    public ProviderServiceView changeStatus(long listingId, ProviderServiceStatusRequest request) {
        long userId = access.currentUserId();
        Provider provider = access.requireActiveAdmin(userId);
        ProviderServiceListing listing = requireMine(listingId, provider.getId());

        int target = request.status() == null ? -1 : request.status();
        if (target == ProviderServiceListing.STATUS_LISTED) {
            access.requireValidQualification(provider.getId());
            requireListable(listing);
            listing.setStatus(ProviderServiceListing.STATUS_LISTED);
        } else if (target == ProviderServiceListing.STATUS_DELISTED) {
            if (!listing.isListed()) {
                throw BusinessException.conflict("该服务项当前不在架上");
            }
            listing.setStatus(ProviderServiceListing.STATUS_DELISTED);
        } else {
            // 0（待审核）与 3（已驳回）是审核流程的结果，服务者设不了
            throw BusinessException.paramInvalid("状态只能是 1（上架）或 2（下架）");
        }
        listingMapper.updateById(listing);

        reviewLog.record(ProviderReviewLog.TARGET_LISTING, listing.getId(), provider.getId(),
                target == ProviderServiceListing.STATUS_LISTED
                        ? ProviderReviewLog.ACTION_LIST
                        : ProviderReviewLog.ACTION_DELIST,
                null);
        return view(listing, null);
    }

    // ---------------------------------------------------------------- 运营侧

    /** 上架审核队列：默认只看待审核。 */
    @Transactional(readOnly = true)
    public PageResult<ProviderServiceView> listForReview(Integer status, Long providerId,
                                                         long page, long pageSize) {
        CurrentUser.requireAdmin();
        Page<ProviderServiceListing> result = listingMapper.selectPage(new Page<>(page, pageSize),
                Wrappers.<ProviderServiceListing>lambdaQuery()
                        .eq(status != null, ProviderServiceListing::getStatus, status)
                        .eq(providerId != null, ProviderServiceListing::getProviderId, providerId)
                        .orderByAsc(ProviderServiceListing::getSubmittedAt)
                        .orderByAsc(ProviderServiceListing::getId));
        Map<String, CatalogQueryApi.ItemInfo> items = catalogItemsOf(result.getRecords());
        Map<Long, String> providerNames = providerNamesOf(result.getRecords());
        return PageResult.from(result, listing -> ProviderViews.toServiceView(
                listing, items.get(listing.getServiceCode()), providerNames.get(listing.getProviderId())));
    }

    /** 上架审核通过：服务项转为「已上架」（提交审核的意图就是上架）。 */
    @Transactional
    public ProviderServiceView approve(long listingId, ReviewApproveRequest request) {
        long reviewerId = CurrentUser.requireAdmin();
        ProviderServiceListing listing = requireListing(listingId);
        requirePending(listing);

        listing.setStatus(ProviderServiceListing.STATUS_LISTED);
        listing.setReviewedAt(AppTime.now());
        listing.setReviewerId(reviewerId);
        listing.setRejectReason(null);
        listingMapper.updateById(listing);

        reviewLog.record(ProviderReviewLog.TARGET_LISTING, listing.getId(), listing.getProviderId(),
                ProviderReviewLog.ACTION_APPROVE, request == null ? null : request.remark());
        return view(listing, null);
    }

    /** 上架审核驳回：原因必填，会展示给服务者。 */
    @Transactional
    public ProviderServiceView reject(long listingId, ReviewRejectRequest request) {
        long reviewerId = CurrentUser.requireAdmin();
        ProviderServiceListing listing = requireListing(listingId);
        requirePending(listing);
        String reason = request.reason().trim();

        listing.setStatus(ProviderServiceListing.STATUS_REJECTED);
        listing.setRejectReason(reason);
        listing.setReviewedAt(AppTime.now());
        listing.setReviewerId(reviewerId);
        listingMapper.updateById(listing);

        reviewLog.record(ProviderReviewLog.TARGET_LISTING, listing.getId(), listing.getProviderId(),
                ProviderReviewLog.ACTION_REJECT, reason);
        return view(listing, null);
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 上架门禁：只有「已下架」（== 上次审核已通过）的服务项能再上架。
     *
     * <p>待审核 / 已驳回都不能上架，这是交付文档 2.5「商家定价 100% 区间校验」（文档用词）之外的
     * 同一条承诺的另一半：价格合法不等于平台看过了。
     */
    private void requireListable(ProviderServiceListing listing) {
        int status = listing.getStatus() == null ? -1 : listing.getStatus();
        switch (status) {
            case ProviderServiceListing.STATUS_DELISTED -> {
                // 正常路径：审核通过后自己下架过，价格没变，可以直接上架
            }
            case ProviderServiceListing.STATUS_PENDING ->
                    throw new BusinessException(ErrorCode.FORBIDDEN, "服务项正在审核中，通过后才能上架");
            case ProviderServiceListing.STATUS_REJECTED ->
                    throw new BusinessException(ErrorCode.FORBIDDEN, "服务项已被驳回，请改价后重新提交审核");
            case ProviderServiceListing.STATUS_LISTED -> throw BusinessException.conflict("该服务项已在架上");
            default -> throw BusinessException.conflict("服务项状态异常，请联系平台");
        }
    }

    private void requirePending(ProviderServiceListing listing) {
        if (listing.getStatus() == null || listing.getStatus() != ProviderServiceListing.STATUS_PENDING) {
            throw BusinessException.conflict("该服务项不在待审核状态（可能已被处理过）");
        }
    }

    private ProviderServiceListing requireMine(long listingId, long providerId) {
        ProviderServiceListing listing = listingMapper.selectOne(Wrappers.<ProviderServiceListing>lambdaQuery()
                .eq(ProviderServiceListing::getId, listingId)
                .eq(ProviderServiceListing::getProviderId, providerId));
        if (listing == null) {
            // 别人的服务项按不存在处理（404 不泄露「这个 id 存在」）
            throw BusinessException.notFound();
        }
        return listing;
    }

    private ProviderServiceListing requireListing(long listingId) {
        ProviderServiceListing listing = listingMapper.selectById(listingId);
        if (listing == null) {
            throw BusinessException.notFound();
        }
        return listing;
    }

    /** 单条视图：跨模块取一次目录项（ADR-0006）。 */
    private ProviderServiceView view(ProviderServiceListing listing, String providerName) {
        return ProviderViews.toServiceView(listing, catalogApi.findItem(listing.getServiceCode()).orElse(null),
                providerName);
    }

    /** 整页视图的目录项：按 code 批量取一次，不是每行查一次。 */
    private Map<String, CatalogQueryApi.ItemInfo> catalogItemsOf(List<ProviderServiceListing> listings) {
        Set<String> codes = listings.stream().map(ProviderServiceListing::getServiceCode)
                .collect(Collectors.toSet());
        return catalogApi.findItems(codes);
    }

    private Map<Long, String> providerNamesOf(List<ProviderServiceListing> listings) {
        Set<Long> ids = listings.stream().map(ProviderServiceListing::getProviderId).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (Provider provider : providerMapper.selectBatchIds(ids)) {
            names.put(provider.getId(), provider.getName());
        }
        return names;
    }
}
