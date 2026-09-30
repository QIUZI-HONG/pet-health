package com.pethealth.provider.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pethealth.api.app.CatalogItemProviderView;
import com.pethealth.api.app.ProviderDetailView;
import com.pethealth.api.app.ProviderQualificationSummaryView;
import com.pethealth.api.app.ProviderServiceOfferView;
import com.pethealth.api.app.ProviderSummaryView;
import com.pethealth.catalog.api.CatalogQueryApi;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.crypto.FieldCipher;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.time.AppTime;
import com.pethealth.common.util.Text;
import com.pethealth.provider.domain.BusinessHours;
import com.pethealth.provider.domain.Provider;
import com.pethealth.provider.domain.ProviderQualification;
import com.pethealth.provider.domain.ProviderServiceListing;
import com.pethealth.provider.mapper.ProviderMapper;
import com.pethealth.provider.mapper.ProviderServiceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * C 端服务者浏览（找店 / 看店）——切片 #104 与 #78 之间欠的那一半（ADR-0047 的推进顺序）。
 *
 * <p><b>为什么要这个接口</b>：号源回答「什么时候能做」、下单回答「总共多少钱」，
 * 但没有一条接口回答「这家店做这个项目多少钱」——用户只能盲点提交，靠订单返回的
 * {@code estimated_pay_amount} 才知道价格。
 *
 * <p><b>两条都只读、都不需要身份</b>（ADR-0037 第一节：游客不是角色，只读接口不需要身份），
 * 所以本类里没有一处 {@code CurrentUser} 调用，也没有归属校验——看得见的东西对谁都一样，
 * **不含一个只对店主人可见的字段**（联系电话是脱敏值，资质不带证件号）。
 *
 * <p><b>可见性口径只有一个</b>（契约的「不存在」包含四种情况）：服务者未过审 / 已驳回 / 已冻结，
 * 或**资质材料全部过期**。前三条判 {@code status}，最后一条与上架门禁共用
 * {@link ProviderAccess#hasValidQualification(java.util.List)}——两处各写一遍的话，
 * 会出现「能上架但搜不到」的店（用户在列表里找不到自己的店，却能在后台看到它在上架）。
 *
 * <p>跨模块只走接口：服务项名称 / 分类 / 单位 / 耗时都来自 ph-catalog（ADR-0006 禁止 join 别人的表），
 * 按 code 一次批量取回，不是逐行查。
 */
@Service
public class ProviderBrowseService {

    /**
     * 「至少有一份未过期且未被驳回的资质」——列表的可见性条件，写成 EXISTS 子查询是为了
     * **让分页在数据库里算对**：先取一页再去掉不合格的店，会得到「这一页 7 条」而不是「符合条件的第 1 页」。
     *
     * <p>{@code provider.id} 是 MyBatis-Plus 生成的表名（{@code provider} 表无别名）；
     * {@code q.is_deleted = 0} 必须显式写：逻辑删除的自动条件是加在**外层实体**上的，
     * 不会钻进子查询。{@code {0}} 是 MyBatis-Plus 的占位符，值以绑定参数下发，不是拼串（无注入面）。
     */
    private static final String QUALIFIED_EXISTS = """
            SELECT 1 FROM provider_qualification q
            WHERE q.provider_id = provider.id
              AND q.is_deleted = 0
              AND (q.status IS NULL OR q.status <> 2)
              AND (q.valid_until IS NULL OR q.valid_until >= {0})""";

    /**
     * 「这家店有这个项目的**在架**服务项」——按项目找店的那一半条件。
     *
     * <p>{@code status = 1} 是写死的（与上面那条同理）：待审核 / 已下架 / 已驳回都不算，
     * 「在架」是用户唯一会下单的东西。
     *
     * <p>与 {@link #QUALIFIED_EXISTS} 同属**可见性口径**，两者必须一起成立；它们的 Java 侧定义是
     * {@link ProviderQualification#countsAsValid(java.time.LocalDate)}（资质那一条）与本类的
     * {@code STATUS_APPROVED}（门店状态那一条）。改其一别忘了另一处：`/providers` 与
     * `/catalog/items/{code}/providers` 两条链路的用例都盯着这两个条件。
     */
    private static final String LISTED_ITEM_EXISTS = """
            SELECT 1 FROM provider_service l
            WHERE l.provider_id = provider.id
              AND l.is_deleted = 0
              AND l.service_code = {0}
              AND l.status = 1""";

    /** 目录项「启用」的取值（{@code service_item.status}）——停用项不对外。 */
    private static final int CATALOG_STATUS_ENABLED = 1;

    private final ProviderMapper providerMapper;
    private final ProviderServiceMapper listingMapper;
    private final ProviderAccess access;
    private final CatalogQueryApi catalogApi;
    private final FieldCipher cipher;

    public ProviderBrowseService(ProviderMapper providerMapper, ProviderServiceMapper listingMapper,
                                 ProviderAccess access, CatalogQueryApi catalogApi, FieldCipher cipher) {
        this.providerMapper = providerMapper;
        this.listingMapper = listingMapper;
        this.access = access;
        this.catalogApi = catalogApi;
        this.cipher = cipher;
    }

    /**
     * 找店：分类 / 关键词 / 分页。
     *
     * <p>关键词只在**门店名称**上匹配：服务项名称不参与——那会让「搜疫苗」把整条街的医院都列出来，
     * 而「哪家有这个项目」是详情页与号源页的事。
     *
     * <p>排序固定**评分降序 + id 升序**：评价体系未落地前 {@code rating} 恒为默认值，
     * 于是实际是稳定按 id 升序——给一个确定的顺序，不把「数据库碰巧返回的顺序」当排序
     * （那种顺序在分页下会让同一条记录忽而出现忽而消失）。
     */
    @Transactional(readOnly = true)
    public PageResult<ProviderSummaryView> browse(Integer type, String keyword, long page, long pageSize) {
        String nameKeyword = Text.trimToNull(keyword);
        Page<Provider> result = providerMapper.selectPage(new Page<>(page, pageSize),
                Wrappers.<Provider>lambdaQuery()
                        .eq(Provider::getStatus, Provider.STATUS_APPROVED)
                        .eq(type != null, Provider::getType, type)
                        .like(nameKeyword != null, Provider::getName, nameKeyword)
                        .exists(QUALIFIED_EXISTS, AppTime.today())
                        // 等级优先：交付文档 2.3 的「等级决定 AI 推荐优先级」（考核算出 provider.level）。
                        // 评价体系未落地时 rating 恒为同一个值，所以同级内实际是按 id 升序——确定且稳定
                        .orderByDesc(Provider::getLevel)
                        .orderByDesc(Provider::getRating)
                        .orderByAsc(Provider::getId));
        return PageResult.from(result, provider -> ProviderViews.toAppSummary(provider, cipher));
    }

    /**
     * 按项目找店：做这个项目的门店与它们的定价（契约 {@code GET /catalog/items/{code}/providers}）。
     *
     * <p>三件事：**项目本身要有效**（不存在 / 已停用 / 已软删一律 40400）、门店要可下单
     * （与 {@link #browse} 同一套条件）、该店对这个项目要有**在架**服务项。
     *
     * <p>分页与排序都在库里算：条件写成外层 {@code provider} 上的两个 EXISTS 子查询，
     * 排序按等级 → 评分 → id。**不在内存里过滤**——先取一页再筛会得到「这一页 7 条」
     * 而不是「符合条件的第 1 页」（同 {@link #browse} 的注释）。
     *
     * <p>页码上的门店拿到之后，再**一次**取回它们对这个项目的在架服务项（定价与 service_id）：
     * 一页最多 100 条，逐行查就是典型的 N+1。
     */
    @Transactional(readOnly = true)
    public PageResult<CatalogItemProviderView> providersOfItem(String itemCode, long page, long pageSize) {
        String code = Text.trimToNull(itemCode);
        CatalogQueryApi.ItemInfo item = code == null ? null : catalogApi.findItem(code).orElse(null);
        if (item == null || item.status() == null || item.status() != CATALOG_STATUS_ENABLED) {
            // 停用与软删对调用方是同一件事：这个项目现在不能对外
            throw BusinessException.notFound("项目不存在或已停用");
        }

        Page<Provider> result = providerMapper.selectPage(new Page<>(page, pageSize),
                Wrappers.<Provider>lambdaQuery()
                        .eq(Provider::getStatus, Provider.STATUS_APPROVED)
                        .exists(QUALIFIED_EXISTS, AppTime.today())
                        .exists(LISTED_ITEM_EXISTS, item.code())
                        .orderByDesc(Provider::getLevel)
                        .orderByDesc(Provider::getRating)
                        .orderByAsc(Provider::getId));

        Map<Long, ProviderServiceListing> listings = listingsByProvider(item.code(),
                result.getRecords().stream().map(Provider::getId).toList());
        return PageResult.from(result, provider -> ProviderViews.toAppItemProvider(
                provider, cipher, listings.get(provider.getId()), item));
    }

    /** 本页门店对这些项目的在架服务项：一次查回，按 providerId 索引（定价与 service_id 都在这）。 */
    private Map<Long, ProviderServiceListing> listingsByProvider(String serviceCode, List<Long> providerIds) {
        if (providerIds.isEmpty()) {
            return Map.of();
        }
        return listingMapper.selectList(Wrappers.<ProviderServiceListing>lambdaQuery()
                        .eq(ProviderServiceListing::getServiceCode, serviceCode)
                        .eq(ProviderServiceListing::getStatus, ProviderServiceListing.STATUS_LISTED)
                        .in(ProviderServiceListing::getProviderId, providerIds))
                .stream()
                // 唯一键 uk_m_s(provider_id, service_code) 保证一户一条，不会覆盖
                .collect(Collectors.toMap(ProviderServiceListing::getProviderId, listing -> listing));
    }

    /**
     * 看店：简介 / 联系方式 / 营业时间 / 资质摘要 / 在架服务项与价格。
     *
     * <p>不可浏览一律 **40400**（与「这个 id 不存在」同码）：对外「看不到」与「不存在」是同一件事，
     * 用 40300 会等于告诉调用方「这个 id 是存在的，只是不给你看」——那是一条可以拿来枚举 id 的信息。
     *
     * <p>文案用**「门店」而不是「服务者」**：这条 message 会原样展示给 C 端用户
     * （`ProviderDetailView` 的 errorMessage 直接用它），而 C 端从标题到按钮一律说「门店」。
     * 「服务者」是平台内部与两个后台的用词（CONTEXT.md 的主体名）——同一屏上两个词指同一个东西，
     * 用户只会以为页面出了错。这句口径记在 CONTEXT.md 的服务者条目下。
     */
    @Transactional(readOnly = true)
    public ProviderDetailView view(long providerId) {
        Provider provider = providerMapper.selectById(providerId);
        if (provider == null) {
            throw BusinessException.notFound("这家门店不存在或当前不可预约");
        }
        List<ProviderQualification> qualifications = access.qualificationsOf(providerId);
        // 资质取一次、判两次（可见性 + 明细），不为了复用判定再查一遍库
        if (!provider.isApproved() || !access.hasValidQualification(qualifications)) {
            throw BusinessException.notFound("这家门店不存在或当前不可预约");
        }
        return ProviderViews.toAppDetail(provider, cipher,
                BusinessHours.decode(provider.getBusinessHours()),
                qualificationSummaries(qualifications),
                serviceOffers(providerId));
    }

    /**
     * 资质摘要：**只给已通过审核且未过期的材料**（契约口径）。
     *
     * <p>与可见性判定用的「未驳回且未过期」刻意不同：可见性只管这家店**能不能下单**
     * （待审材料也算数，否则刚补交完材料的店会凭空消失），而摘要要展示的是**已经核过的材料**——
     * 把待审的材料摆给用户看，等于把「平台还没核」说成「平台核过了」。
     */
    private static List<ProviderQualificationSummaryView> qualificationSummaries(
            List<ProviderQualification> qualifications) {
        LocalDate today = AppTime.today();
        return qualifications.stream()
                .filter(qualification -> qualification.getStatus() != null
                        && qualification.getStatus() == ProviderQualification.STATUS_APPROVED)
                .filter(qualification -> !qualification.isExpiredAt(today))
                .sorted(Comparator.comparing(ProviderQualification::getType,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(ProviderQualification::getId,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .map(ProviderViews::toAppQualification)
                .toList();
    }

    /**
     * 在架服务项：只有 {@code status=1}（已上架）——待审核 / 已下架 / 已驳回都不出现，
     * 把待审的项列出来等于给用户一个点了会 40400 的按钮。
     *
     * <p>排序按分类 + 服务项名称（契约口径）：同一家店的项目按目录分类聚在一起，
     * 用户找「洗护」还是「疫苗」不必自己扫一遍。
     */
    private List<ProviderServiceOfferView> serviceOffers(long providerId) {
        List<ProviderServiceListing> listings = listingMapper.selectList(
                Wrappers.<ProviderServiceListing>lambdaQuery()
                        .eq(ProviderServiceListing::getProviderId, providerId)
                        .eq(ProviderServiceListing::getStatus, ProviderServiceListing.STATUS_LISTED));
        if (listings.isEmpty()) {
            return List.of();
        }
        Set<String> codes = listings.stream().map(ProviderServiceListing::getServiceCode)
                .collect(Collectors.toSet());
        Map<String, CatalogQueryApi.ItemInfo> items = catalogApi.findItems(codes);
        return listings.stream()
                .map(listing -> ProviderViews.toAppServiceOffer(listing, items.get(listing.getServiceCode())))
                .sorted(Comparator.comparing(ProviderServiceOfferView::categoryCode,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(ProviderServiceOfferView::serviceName,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        // 目录项被软删时名称与分类都是 null，此时按 id 兜一个稳定顺序
                        .thenComparing(ProviderServiceOfferView::id))
                .toList();
    }
}
