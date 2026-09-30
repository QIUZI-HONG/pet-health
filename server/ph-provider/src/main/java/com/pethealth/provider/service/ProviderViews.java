package com.pethealth.provider.service;

import com.pethealth.api.app.CatalogItemProviderView;
import com.pethealth.api.app.ProviderDetailView;
import com.pethealth.api.app.ProviderQualificationSummaryView;
import com.pethealth.api.app.ProviderServiceOfferView;
import com.pethealth.api.app.ProviderSummaryView;
import com.pethealth.api.catalog.CatalogItemProposalSummary;
import com.pethealth.api.catalog.CatalogItemProposalView;
import com.pethealth.api.provider.BusinessHour;
import com.pethealth.api.provider.OnboardingApplicationSummary;
import com.pethealth.api.provider.OnboardingApplicationView;
import com.pethealth.api.provider.ProviderProfileView;
import com.pethealth.api.provider.ProviderQualificationView;
import com.pethealth.api.provider.ProviderServiceView;
import com.pethealth.api.provider.ReviewLogView;
import com.pethealth.catalog.api.CatalogQueryApi;
import com.pethealth.catalog.api.Price;
import com.pethealth.common.crypto.FieldCipher;
import com.pethealth.common.util.Masking;
import com.pethealth.provider.domain.BusinessHours;
import com.pethealth.provider.domain.CatalogItemProposal;
import com.pethealth.provider.domain.OnboardingApplication;
import com.pethealth.provider.domain.Provider;
import com.pethealth.provider.domain.ProviderQualification;
import com.pethealth.provider.domain.ProviderReviewLog;
import com.pethealth.provider.domain.ProviderServiceListing;

import java.util.List;

/**
 * 实体 → 契约视图的映射。**只在这一个类里做**，理由有三条：
 *
 * <ul>
 *   <li><b>脱敏只有一处</b>：手机号与证件号必须解密后打码（ADR-0013），散在几个 service 里
 *       早晚会有一个路径忘了脱敏，而这类错一旦发生就追不回来；
 *   <li><b>跨模块拼装只有一处</b>：服务项要拼目录侧的名称与区间（ADR-0006 的接口调用），
 *       拼装规则写在这里，service 只管业务分支；
 *   <li><b>列表与详情的字段差异只有一处</b>：摘要少几个字段是刻意的（列表不需要资质明细），
 *       这种差异写在映射里比写在 SQL 里好读。
 * </ul>
 */
final class ProviderViews {

    private ProviderViews() {
    }

    /** 证件号的脱敏：保留前 4 后 4，中间打码。长度不足以这样处理时整串打码。 */
    static String maskCertNo(String plain) {
        if (plain == null || plain.isBlank()) {
            return null;
        }
        String value = plain.trim();
        if (value.length() <= 8) {
            return value.charAt(0) + "*".repeat(Math.max(1, value.length() - 2))
                    + value.charAt(value.length() - 1);
        }
        return value.substring(0, 4) + "*".repeat(value.length() - 8) + value.substring(value.length() - 4);
    }

    static ProviderProfileView toProfileView(Provider provider, FieldCipher cipher) {
        return new ProviderProfileView(
                provider.getId(),
                provider.getName(),
                provider.getType(),
                provider.getCategory(),
                provider.getLogo(),
                provider.getIntro(),
                provider.getAddress(),
                plain(provider.getLng()),
                plain(provider.getLat()),
                Masking.phone(cipher.decrypt(provider.getPhoneEnc())),
                BusinessHours.decode(provider.getBusinessHours()),
                provider.getStatus(),
                provider.getLevel(),
                provider.getRegionCode(),
                Price.format(provider.getMonthlyScore()),
                Price.format(provider.getRating()),
                provider.getApprovedAt(),
                provider.getCreatedAt(),
                provider.getUpdatedAt());
    }

    static ProviderQualificationView toQualificationView(ProviderQualification qualification, FieldCipher cipher) {
        String certNo = qualification.getCertNoEnc() == null
                ? null
                : maskCertNo(cipher.decrypt(qualification.getCertNoEnc()));
        return new ProviderQualificationView(
                qualification.getId(),
                qualification.getType(),
                qualification.getName(),
                certNo,
                qualification.getFileUrl(),
                qualification.getValidFrom(),
                qualification.getValidUntil(),
                qualification.getStatus(),
                qualification.getReviewRemark());
    }

    static ReviewLogView toLogView(ProviderReviewLog log) {
        return new ReviewLogView(
                log.getId(),
                log.getTargetType(),
                log.getAction(),
                log.getActorId(),
                log.getActorDomain(),
                log.getRemark(),
                log.getCreatedAt());
    }

    /**
     * 列表行：不带资质明细与审核流水（列表要点开才看细节），但**联系电话仍要解密后脱敏**——
     * 运营在队列里要靠电话联系服务者，而密文对谁都没用。
     */
    static OnboardingApplicationSummary toSummary(OnboardingApplication application, Provider provider,
                                                  FieldCipher cipher) {
        return new OnboardingApplicationSummary(
                application.getId(),
                application.getProviderId(),
                provider.getName(),
                provider.getType(),
                application.getApplicantUserId(),
                application.getApplicantName(),
                Masking.phone(cipher.decrypt(application.getContactPhoneEnc())),
                application.getStatus(),
                application.getRejectReason(),
                application.getSubmitCount(),
                application.getSubmittedAt(),
                application.getReviewedAt());
    }

    static OnboardingApplicationView toDetail(OnboardingApplication application, Provider provider,
                                              List<ProviderQualification> qualifications,
                                              List<ProviderReviewLog> logs, FieldCipher cipher) {
        return new OnboardingApplicationView(
                application.getId(),
                application.getStatus(),
                application.getRejectReason(),
                application.getReviewRemark(),
                application.getSubmitCount(),
                application.getSubmittedAt(),
                application.getReviewedAt(),
                application.getReviewerId(),
                application.getApplicantUserId(),
                application.getApplicantName(),
                Masking.phone(cipher.decrypt(application.getContactPhoneEnc())),
                toProfileView(provider, cipher),
                qualifications.stream().map(qualification -> toQualificationView(qualification, cipher)).toList(),
                logs.stream().map(ProviderViews::toLogView).toList());
    }

    static ProviderServiceView toServiceView(ProviderServiceListing listing, CatalogQueryApi.ItemInfo item,
                                             String providerName) {
        return new ProviderServiceView(
                listing.getId(),
                listing.getProviderId(),
                providerName,
                listing.getServiceCode(),
                item == null ? null : item.name(),
                item == null ? null : item.categoryCode(),
                item == null ? null : item.categoryName(),
                Price.format(listing.getPrice()),
                item == null ? null : item.priceUnit(),
                item == null ? null : Price.format(item.priceRange().min()),
                item == null ? null : Price.format(item.priceRange().max()),
                listing.getStatus(),
                listing.getRejectReason(),
                listing.getSubmittedAt(),
                listing.getReviewedAt(),
                listing.getUpdatedAt());
    }

    static CatalogItemProposalSummary toProposalSummary(CatalogItemProposal proposal, Provider provider,
                                                        String categoryName) {
        return new CatalogItemProposalSummary(
                proposal.getId(),
                proposal.getProviderId(),
                provider == null ? null : provider.getName(),
                proposal.getCategoryCode(),
                categoryName,
                proposal.getName(),
                Price.format(proposal.getSuggestedPriceMin()),
                Price.format(proposal.getSuggestedPriceMax()),
                proposal.getSuggestedPriceUnit(),
                proposal.getStatus(),
                proposal.getRejectReason(),
                proposal.getItemCode(),
                proposal.getSubmittedAt(),
                proposal.getReviewedAt());
    }

    static CatalogItemProposalView toProposalView(CatalogItemProposal proposal, Provider provider,
                                                  String categoryName, List<ProviderReviewLog> logs) {
        return new CatalogItemProposalView(
                proposal.getId(),
                proposal.getProviderId(),
                provider == null ? null : provider.getName(),
                proposal.getCategoryCode(),
                categoryName,
                proposal.getName(),
                proposal.getDescription(),
                Price.format(proposal.getSuggestedPriceMin()),
                Price.format(proposal.getSuggestedPriceMax()),
                proposal.getSuggestedPriceUnit(),
                proposal.getStatus(),
                proposal.getRejectReason(),
                proposal.getItemCode(),
                proposal.getReviewRemark(),
                proposal.getSubmittedAt(),
                proposal.getReviewedAt(),
                proposal.getReviewerId(),
                logs.stream().map(ProviderViews::toLogView).toList());
    }

    // ---------------------------------------------------------------- C 端浏览（`/api/v1/app/providers`，只读）

    /**
     * 列表行（找店）。**不放 status / level / monthlyScore**：能进列表说明状态与资质都过了门槛，
     * 再给一个状态字段只会让前端有机会把它当成「能不能下单」的第二个判据（口径在契约里）。
     */
    static ProviderSummaryView toAppSummary(Provider provider, FieldCipher cipher) {
        return new ProviderSummaryView(
                provider.getId(),
                provider.getName(),
                provider.getType(),
                Provider.typeName(provider.getType()),
                provider.getLogo(),
                provider.getIntro(),
                provider.getAddress(),
                plain(provider.getLng()),
                plain(provider.getLat()),
                Masking.phone(cipher.decrypt(provider.getPhoneEnc())),
                Price.format(provider.getRating()));
    }

    /** 详情（看店）：简介 / 联系方式 / 营业时间 / 资质摘要 / **在架服务项与价格**。 */
    static ProviderDetailView toAppDetail(Provider provider, FieldCipher cipher, List<BusinessHour> businessHours,
                                          List<ProviderQualificationSummaryView> qualifications,
                                          List<ProviderServiceOfferView> services) {
        return new ProviderDetailView(
                provider.getId(),
                provider.getName(),
                provider.getType(),
                Provider.typeName(provider.getType()),
                provider.getLogo(),
                provider.getIntro(),
                provider.getAddress(),
                plain(provider.getLng()),
                plain(provider.getLat()),
                Masking.phone(cipher.decrypt(provider.getPhoneEnc())),
                Price.format(provider.getRating()),
                businessHours,
                qualifications,
                services);
    }

    /**
     * 资质摘要（C 端）。**不解密证件号**——那张 schema 里根本没有这个字段，
     * 不给出入口就绕不开（与服务者侧的 {@link #toQualificationView} 的差别就在这一句）。
     */
    static ProviderQualificationSummaryView toAppQualification(ProviderQualification qualification) {
        return new ProviderQualificationSummaryView(
                qualification.getType(),
                ProviderQualification.typeName(qualification.getType()),
                qualification.getName(),
                qualification.getValidUntil() == null ? null : qualification.getValidUntil().toString());
    }

    /** 在架服务项：名称 / 分类 / 单位 / 耗时都来自目录侧现取（{@code item} 可能为空——目录项被软删了）。 */
    static ProviderServiceOfferView toAppServiceOffer(ProviderServiceListing listing, CatalogQueryApi.ItemInfo item) {
        return new ProviderServiceOfferView(
                listing.getId(),
                listing.getServiceCode(),
                item == null ? null : item.name(),
                item == null ? null : item.categoryCode(),
                item == null ? null : item.categoryName(),
                Price.format(listing.getPrice()),
                item == null ? null : item.priceUnit(),
                item == null ? null : item.durationMinutes());
    }

    /** 按项目找店的一行：门店对外字段 + **这个项目在这家店的定价** + 下单要用的 {@code serviceId}。 */
    static CatalogItemProviderView toAppItemProvider(Provider provider, FieldCipher cipher,
                                                     ProviderServiceListing listing,
                                                     CatalogQueryApi.ItemInfo item) {
        return new CatalogItemProviderView(
                provider.getId(),
                provider.getName(),
                provider.getType(),
                Provider.typeName(provider.getType()),
                Price.format(provider.getRating()),
                provider.getAddress(),
                Masking.phone(cipher.decrypt(provider.getPhoneEnc())),
                listing == null ? null : listing.getId(),
                listing == null ? null : Price.format(listing.getPrice()),
                item == null ? null : item.priceUnit());
    }

    /** 小数一律以字符串出（ADR-0011：JS 里丢精度）。 */
    private static String plain(java.math.BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }
}
