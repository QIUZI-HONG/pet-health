package com.pethealth.provider.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pethealth.api.provider.BusinessHour;
import com.pethealth.api.provider.BusinessHoursRequest;
import com.pethealth.api.provider.ProviderProfileRequest;
import com.pethealth.api.provider.ProviderProfileView;
import com.pethealth.api.provider.ProviderQualificationRequest;
import com.pethealth.common.crypto.FieldCipher;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.util.Text;
import com.pethealth.provider.domain.BusinessHours;
import com.pethealth.provider.domain.Coordinate;
import com.pethealth.provider.domain.Provider;
import com.pethealth.provider.domain.ProviderQualification;
import com.pethealth.provider.mapper.ProviderMapper;
import com.pethealth.provider.mapper.ProviderQualificationMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 门店信息与营业时间：**审核通过后**才可维护（交付文档 #104 的验收项，「AC：审核通过后可维护
 * 门店信息与营业时间」）。
 *
 * <p>三条口径：
 *
 * <ul>
 *   <li><b>读不设门禁、写设门禁</b>：待审核 / 驳回 / 冻结都能读自己的门店（否则前端只有一个 404
 *       可展示），但只有正常状态能改；
 *   <li><b>门店信息变更不需要重新审核</b>：交付文档没要求，本切片不自行加一道审核
 *       （被否决的选项与代价见 ADR-0035）。若日后真需要，触发点已经在这里，加一个状态即可；
 *   <li><b>补交资质材料即可恢复上架</b>（2026-09-29 口径）：材料过期后由
 *       {@link QualificationExpiryJob} 自动下架全部服务项，服务者补交新材料就能再上架——
 *       不必等运营复核，因为「补交」这个动作本身就是他重新作出承诺。
 *       材料复核流程（谁看、多久看、驳回后怎么办）尚未定，列入 ADR-0035 的待澄清。
 * </ul>
 */
@Service
public class ProviderProfileService {

    private final ProviderMapper providerMapper;
    private final ProviderQualificationMapper qualificationMapper;
    private final ProviderAccess access;
    private final QualificationGuard qualificationGuard;
    private final FieldCipher cipher;

    public ProviderProfileService(ProviderMapper providerMapper,
                                  ProviderQualificationMapper qualificationMapper,
                                  ProviderAccess access,
                                  QualificationGuard qualificationGuard,
                                  FieldCipher cipher) {
        this.providerMapper = providerMapper;
        this.qualificationMapper = qualificationMapper;
        this.access = access;
        this.qualificationGuard = qualificationGuard;
        this.cipher = cipher;
    }

    /** 我的门店（含营业时间与脱敏电话）；未绑定服务者 → 40400。 */
    @Transactional(readOnly = true)
    public ProviderProfileView get() {
        long userId = access.currentUserId();
        return ProviderViews.toProfileView(access.requireBound(userId), cipher);
    }

    @Transactional
    public ProviderProfileView update(ProviderProfileRequest request) {
        long userId = access.currentUserId();
        Provider provider = access.requireActiveAdmin(userId);
        provider.setName(request.name().trim());
        provider.setLogo(Text.trimToNull(request.logo()));
        provider.setIntro(Text.trimToNull(request.intro()));
        provider.setAddress(request.address().trim());
        provider.setLng(Coordinate.longitude(request.lng()));
        provider.setLat(Coordinate.latitude(request.lat()));
        provider.setPhoneEnc(cipher.encrypt(request.phone().trim()));
        provider.setPhoneHash(cipher.lookupHash(request.phone().trim()));
        providerMapper.updateById(provider);
        return ProviderViews.toProfileView(provider, cipher);
    }

    /**
     * 维护营业时间（整体替换，空数组表示整周休息）。
     *
     * <p>校验三条：一周内不能有重复的星期；每天的开始时间要早于结束时间；跨天（22:00–02:00）
     * 本期不支持——预约时段校验在下单链路（#77），到那时再决定要不要跨天，现在不编。
     */
    @Transactional
    public ProviderProfileView updateBusinessHours(BusinessHoursRequest request) {
        long userId = access.currentUserId();
        Provider provider = access.requireActiveAdmin(userId);
        List<BusinessHour> hours = request.hours() == null ? List.of() : validateHours(request.hours());
        provider.setBusinessHours(BusinessHours.encode(hours));
        providerMapper.updateById(provider);
        return ProviderViews.toProfileView(provider, cipher);
    }

    /**
     * 补交 / 更新资质材料：整体替换，新材料回到「待审核」。
     *
     * <p>旧的整批换掉（逻辑删除，可追溯）：资质是「当前有效的那一版」，留着过期的那份
     * 只会让上架门禁的判断变模糊。
     */
    @Transactional
    public ProviderProfileView resubmitQualifications(List<ProviderQualificationRequest> requests) {
        long userId = access.currentUserId();
        Provider provider = access.requireActiveAdmin(userId);
        if (requests == null || requests.isEmpty()) {
            throw BusinessException.paramInvalid("至少提交一份资质材料");
        }
        qualificationGuard.requireUniqueCertificates(requests, provider.getId());

        qualificationMapper.delete(Wrappers.<ProviderQualification>lambdaQuery()
                .eq(ProviderQualification::getProviderId, provider.getId()));
        for (ProviderQualificationRequest request : requests) {
            ProviderQualification qualification = new ProviderQualification();
            qualification.setProviderId(provider.getId());
            qualification.setType(request.type());
            qualification.setName(request.name() == null || request.name().isBlank()
                    ? "资质材料"
                    : request.name().trim());
            String certNo = Text.trimToNull(request.certNo());
            qualification.setCertNoEnc(certNo == null ? null : cipher.encrypt(certNo));
            qualification.setCertNoHash(certNo == null ? null : cipher.lookupHash(certNo));
            qualification.setFileUrl(Text.trimToNull(request.fileUrl()));
            qualification.setValidFrom(request.validFrom());
            qualification.setValidUntil(request.validUntil());
            qualification.setStatus(ProviderQualification.STATUS_PENDING);
            qualificationMapper.insert(qualification);
        }
        return ProviderViews.toProfileView(provider, cipher);
    }

    private static List<BusinessHour> validateHours(List<BusinessHour> hours) {
        Set<Integer> seen = new HashSet<>();
        List<BusinessHour> sorted = new ArrayList<>(hours);
        sorted.sort(Comparator.comparingInt(BusinessHour::dayOfWeek));
        for (BusinessHour hour : sorted) {
            if (!seen.add(hour.dayOfWeek())) {
                throw BusinessException.paramInvalid("星期 " + hour.dayOfWeek() + " 有重复的营业时段");
            }
            if (hour.openTime().compareTo(hour.closeTime()) >= 0) {
                throw BusinessException.paramInvalid("星期 " + hour.dayOfWeek() + " 的开始时间必须早于结束时间");
            }
        }
        return sorted;
    }
}
