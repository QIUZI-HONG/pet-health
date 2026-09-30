package com.pethealth.provider.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pethealth.api.admin.ReviewApproveRequest;
import com.pethealth.api.admin.ReviewRejectRequest;
import com.pethealth.api.provider.OnboardingApplicationRequest;
import com.pethealth.api.provider.OnboardingApplicationSummary;
import com.pethealth.api.provider.OnboardingApplicationView;
import com.pethealth.api.provider.ProviderQualificationRequest;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.crypto.FieldCipher;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.time.AppTime;
import com.pethealth.common.util.Text;
import com.pethealth.provider.domain.Coordinate;
import com.pethealth.provider.domain.OnboardingApplication;
import com.pethealth.provider.domain.Provider;
import com.pethealth.provider.domain.ProviderQualification;
import com.pethealth.provider.domain.ProviderReviewLog;
import com.pethealth.provider.mapper.OnboardingApplicationMapper;
import com.pethealth.provider.mapper.ProviderMapper;
import com.pethealth.provider.mapper.ProviderQualificationMapper;
import com.pethealth.provider.mapper.ProviderReviewLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 服务者入驻与资质审核（BPM-4 的起点，切片 #104），决策见 ADR-0035。
 *
 * <p>流程（2026-09-29 项目所有者拍板）：申请 → <b>单人审核</b>（运营）→ 驳回可改后重提
 * → 通过后成为正式服务者。五条要点：
 *
 * <ul>
 *   <li><b>驳回重提不新建服务者记录</b>：申请即建一条待审核的 {@code provider}，
 *       重提是改这一条 + {@code submitCount} 加一，于是「被退回过几次」查得到；
 *   <li><b>审核流水 append-only</b>：每一次提交、重提、通过、驳回都进
 *       {@code provider_review_log}（谁、何时、结论、理由）——申请单上只留最后一次结论；
 *   <li><b>通过的那一刻绑定账号</b>：申请人成为该服务者的管理员（技师是管理员的权限子集，
 *       兼任时共用一条记录）；
 *   <li><b>唯一性</b>：同一营业执照 / 身份证只允许一个「有效状态」（待审核或正常）的服务者，
 *       被驳回或已清退的不占名额——否则一次误拒就再也开不了店；
 *   <li><b>资质到期</b>：留给 {@link QualificationExpiryJob}——过期后自动下架全部服务项，
 *       但不注销服务者（历史订单与客户关系要留着）。
 * </ul>
 *
 * <p>审核是单人的：本切片不做「初审 + 复审」双人流程（交付文档没要求，09-22 那版规划把它列为
 * 待定项 D9）。哪张票、什么条件下要加第二级审批，写在 ADR-0035。
 */
@Service
public class OnboardingService {

    private final ProviderMapper providerMapper;
    private final ProviderQualificationMapper qualificationMapper;
    private final OnboardingApplicationMapper applicationMapper;
    private final ProviderReviewLogMapper reviewLogMapper;
    private final ReviewLogRecorder reviewLog;
    private final ProviderAccess access;
    private final QualificationGuard qualificationGuard;
    private final FieldCipher cipher;

    public OnboardingService(ProviderMapper providerMapper,
                            ProviderQualificationMapper qualificationMapper,
                            OnboardingApplicationMapper applicationMapper,
                            ProviderReviewLogMapper reviewLogMapper,
                            ReviewLogRecorder reviewLog,
                            ProviderAccess access,
                            QualificationGuard qualificationGuard,
                            FieldCipher cipher) {
        this.providerMapper = providerMapper;
        this.qualificationMapper = qualificationMapper;
        this.applicationMapper = applicationMapper;
        this.reviewLogMapper = reviewLogMapper;
        this.reviewLog = reviewLog;
        this.access = access;
        this.qualificationGuard = qualificationGuard;
        this.cipher = cipher;
    }

    // ---------------------------------------------------------------- 服务者侧

    /** 提交入驻申请：建服务者（待审核）+ 资质材料 + 申请单，并留一条「提交」流水。 */
    @Transactional
    public OnboardingApplicationView submit(OnboardingApplicationRequest request) {
        long userId = access.currentUserId();
        requireNotBound(userId);
        requireNoExistingApplication(userId);
        qualificationGuard.requireUniqueCertificates(request.qualifications(), null);

        Provider provider = new Provider();
        provider.setStatus(Provider.STATUS_PENDING);
        provider.setLevel(1);
        provider.setMonthlyScore(BigDecimal.ZERO);
        provider.setRating(new BigDecimal("5.0"));
        applyProfile(provider, request);
        saveContactPhone(provider, request);
        providerMapper.insert(provider);

        replaceQualifications(provider.getId(), request.qualifications(), ProviderQualification.STATUS_PENDING);

        OnboardingApplication application = new OnboardingApplication();
        application.setProviderId(provider.getId());
        application.setApplicantUserId(userId);
        application.setApplicantName(request.applicantName().trim());
        application.setContactPhoneEnc(cipher.encrypt(request.contactPhone().trim()));
        application.setContactPhoneHash(cipher.lookupHash(request.contactPhone().trim()));
        application.setStatus(OnboardingApplication.STATUS_PENDING);
        application.setSubmitCount(1);
        application.setSubmittedAt(AppTime.now());
        applicationMapper.insert(application);

        reviewLog.record(ProviderReviewLog.TARGET_ONBOARDING, application.getId(), provider.getId(),
                ProviderReviewLog.ACTION_SUBMIT, null);
        return detail(application, provider);
    }

    /**
     * 驳回后修改重提：**同一份申请单**改完回到待审，不新建服务者记录。
     *
     * <p>只有「已驳回」的申请能重提。待审核时直接改会绕过审核员手里的那一版材料——
     * 他看到的和最终入库的不是同一份，这种事必须由状态机挡住。
     */
    @Transactional
    public OnboardingApplicationView resubmit(long applicationId, OnboardingApplicationRequest request) {
        long userId = access.currentUserId();
        OnboardingApplication application = requireMine(applicationId, userId);
        if (!application.isRejected()) {
            throw BusinessException.conflict(application.isPending()
                    ? "申请正在审核中，不能修改；如需变更请联系平台运营"
                    : "申请已通过，不能重新提交");
        }
        Provider provider = access.requireById(application.getProviderId());
        qualificationGuard.requireUniqueCertificates(request.qualifications(), provider.getId());

        applyProfile(provider, request);
        provider.setStatus(Provider.STATUS_PENDING);
        provider.setPhoneEnc(cipher.encrypt(request.contactPhone().trim()));
        provider.setPhoneHash(cipher.lookupHash(request.contactPhone().trim()));
        providerMapper.updateById(provider);

        // 旧材料整批换掉（逻辑删除，留着可追溯），新的一批回到待审
        qualificationMapper.delete(Wrappers.<ProviderQualification>lambdaQuery()
                .eq(ProviderQualification::getProviderId, provider.getId()));
        replaceQualifications(provider.getId(), request.qualifications(), ProviderQualification.STATUS_PENDING);

        application.setApplicantName(request.applicantName().trim());
        application.setContactPhoneEnc(cipher.encrypt(request.contactPhone().trim()));
        application.setContactPhoneHash(cipher.lookupHash(request.contactPhone().trim()));
        application.setStatus(OnboardingApplication.STATUS_PENDING);
        application.setRejectReason(null);
        // 上一轮的审核结论进流水（下一次驳回会写新的），申请单上不再显示已作废的结论
        application.setReviewedAt(null);
        application.setReviewRemark(null);
        application.setSubmitCount((application.getSubmitCount() == null ? 1 : application.getSubmitCount()) + 1);
        application.setSubmittedAt(AppTime.now());
        applicationMapper.updateById(application);

        reviewLog.record(ProviderReviewLog.TARGET_ONBOARDING, application.getId(), provider.getId(),
                ProviderReviewLog.ACTION_RESUBMIT, null);
        return detail(application, provider);
    }

    /** 我的申请列表（进度与驳回原因）。 */
    @Transactional(readOnly = true)
    public PageResult<OnboardingApplicationSummary> listMine(Integer status, long page, long pageSize) {
        long userId = access.currentUserId();
        Page<OnboardingApplication> result = applicationMapper.selectPage(new Page<>(page, pageSize),
                Wrappers.<OnboardingApplication>lambdaQuery()
                        .eq(OnboardingApplication::getApplicantUserId, userId)
                        .eq(status != null, OnboardingApplication::getStatus, status)
                        .orderByDesc(OnboardingApplication::getSubmittedAt)
                        .orderByDesc(OnboardingApplication::getId));
        return PageResult.from(result, application -> ProviderViews.toSummary(
                application, access.requireById(application.getProviderId()), cipher));
    }

    /** 我的申请详情（别人的申请一律 40400，与「不存在」同码）。 */
    @Transactional(readOnly = true)
    public OnboardingApplicationView getMine(long applicationId) {
        long userId = access.currentUserId();
        OnboardingApplication application = requireMine(applicationId, userId);
        return detail(application, access.requireById(application.getProviderId()));
    }

    // ---------------------------------------------------------------- 运营侧

    /** 审核队列：默认只看待审核（先到先审），可以按状态查历史。 */
    @Transactional(readOnly = true)
    public PageResult<OnboardingApplicationSummary> listForReview(Integer status, long page, long pageSize) {
        CurrentUser.requireAdmin();
        Page<OnboardingApplication> result = applicationMapper.selectPage(new Page<>(page, pageSize),
                Wrappers.<OnboardingApplication>lambdaQuery()
                        .eq(status != null, OnboardingApplication::getStatus, status)
                        .orderByAsc(OnboardingApplication::getStatus)
                        .orderByAsc(OnboardingApplication::getSubmittedAt)
                        .orderByAsc(OnboardingApplication::getId));
        return PageResult.from(result, application -> ProviderViews.toSummary(
                application, access.requireById(application.getProviderId()), cipher));
    }

    @Transactional(readOnly = true)
    public OnboardingApplicationView getForReview(long applicationId) {
        CurrentUser.requireAdmin();
        OnboardingApplication application = requireApplication(applicationId);
        return detail(application, access.requireById(application.getProviderId()));
    }

    /** 审核通过：服务者转为正常、材料转通过、申请人绑定为管理员。 */
    @Transactional
    public OnboardingApplicationView approve(long applicationId, ReviewApproveRequest request) {
        long reviewerId = CurrentUser.requireAdmin();
        OnboardingApplication application = requireApplication(applicationId);
        requirePending(application);
        Provider provider = access.requireById(application.getProviderId());

        provider.setStatus(Provider.STATUS_APPROVED);
        provider.setApprovedAt(AppTime.now());
        providerMapper.updateById(provider);

        for (ProviderQualification qualification : access.qualificationsOf(provider.getId())) {
            qualification.setStatus(ProviderQualification.STATUS_APPROVED);
            qualificationMapper.updateById(qualification);
        }

        application.setStatus(OnboardingApplication.STATUS_APPROVED);
        application.setReviewedAt(AppTime.now());
        application.setReviewerId(reviewerId);
        application.setReviewRemark(Text.trimToNull(request == null ? null : request.remark()));
        applicationMapper.updateById(application);

        access.bindAdmin(provider.getId(), application.getApplicantUserId());
        reviewLog.record(ProviderReviewLog.TARGET_ONBOARDING, application.getId(), provider.getId(),
                ProviderReviewLog.ACTION_APPROVE, application.getReviewRemark());
        return detail(application, provider);
    }

    /**
     * 审核驳回：原因必填（会原样展示给服务者）。
     *
     * <p>材料一并标为驳回，避免「申请被退回了、材料还显示待审」这种自相矛盾的状态。
     */
    @Transactional
    public OnboardingApplicationView reject(long applicationId, ReviewRejectRequest request) {
        long reviewerId = CurrentUser.requireAdmin();
        OnboardingApplication application = requireApplication(applicationId);
        requirePending(application);
        Provider provider = access.requireById(application.getProviderId());
        String reason = request.reason().trim();

        provider.setStatus(Provider.STATUS_REJECTED);
        providerMapper.updateById(provider);

        for (ProviderQualification qualification : access.qualificationsOf(provider.getId())) {
            qualification.setStatus(ProviderQualification.STATUS_REJECTED);
            qualification.setReviewRemark(reason);
            qualificationMapper.updateById(qualification);
        }

        application.setStatus(OnboardingApplication.STATUS_REJECTED);
        application.setRejectReason(reason);
        application.setReviewedAt(AppTime.now());
        application.setReviewerId(reviewerId);
        applicationMapper.updateById(application);

        reviewLog.record(ProviderReviewLog.TARGET_ONBOARDING, application.getId(), provider.getId(),
                ProviderReviewLog.ACTION_REJECT, reason);
        return detail(application, provider);
    }

    // ---------------------------------------------------------------- 内部

    /** 提交与重提都要能改到的档案字段在这里只写一遍。经纬度走 {@link #coordinate} 的范围校验：
     *  要么都给要么都不给——坐标错到月球上去没人会发现，所以在这里拦。 */
    private void applyProfile(Provider provider, OnboardingApplicationRequest request) {
        provider.setName(request.name().trim());
        provider.setType(request.type());
        provider.setCategory(request.category() == null ? 1 : request.category());
        provider.setLogo(Text.trimToNull(request.logo()));
        provider.setIntro(Text.trimToNull(request.intro()));
        provider.setAddress(request.address().trim());
        provider.setLng(Coordinate.longitude(request.lng()));
        provider.setLat(Coordinate.latitude(request.lat()));
    }

    /** 写服务者手机号的密文 + 等值索引（ADR-0013）。两列必须成对：只有密文就搜不到人，
     *  只有索引就回显不出号。{@code resubmit} 里那两行是同一个动作的另一处副本，改口径要一起改。 */
    private void saveContactPhone(Provider provider, OnboardingApplicationRequest request) {
        provider.setPhoneEnc(cipher.encrypt(request.contactPhone().trim()));
        provider.setPhoneHash(cipher.lookupHash(request.contactPhone().trim()));
    }

    /** 一批材料**整批插入**，不是逐条 upsert：重提时调用方先把旧批逻辑删掉再调它（见 {@code resubmit}），
     *  所以这里只管写新行。{@code status} 由调用方给（提交与重提都是待审）。 */
    private void replaceQualifications(long providerId, List<ProviderQualificationRequest> requests, int status) {
        for (ProviderQualificationRequest request : requests) {
            ProviderQualification qualification = new ProviderQualification();
            qualification.setProviderId(providerId);
            qualification.setType(request.type());
            qualification.setName(request.name() == null || request.name().isBlank()
                    ? defaultQualificationName(request.type())
                    : request.name().trim());
            String certNo = Text.trimToNull(request.certNo());
            qualification.setCertNoEnc(certNo == null ? null : cipher.encrypt(certNo));
            qualification.setCertNoHash(certNo == null ? null : cipher.lookupHash(certNo));
            qualification.setFileUrl(Text.trimToNull(request.fileUrl()));
            qualification.setValidFrom(request.validFrom());
            qualification.setValidUntil(request.validUntil());
            qualification.setStatus(status);
            qualificationMapper.insert(qualification);
        }
    }

    /** 一个账号只能关联一个服务者（技师是管理员的权限子集，兼任时共用同一条记录），
     *  已绑定的账号再申请就是 40900。 */
    private void requireNotBound(long userId) {
        if (access.findBound(userId) != null) {
            throw BusinessException.conflict("当前账号已关联服务者，不能重复申请");
        }
    }

    /**
     * 一个账号只能有一份申请单（不论状态）。
     *
     * <p>被驳回后**不是重新提一份**，而是改那一份（{@code PUT}）——这是「驳回重提不新建服务者记录」
     * 在接口层的落点：允许 POST 新建的话，一次驳回就会在库里留下两个 {@code provider}，
     * 其中一个是永远不会被使用的空壳。
     */
    private void requireNoExistingApplication(long userId) {
        OnboardingApplication existing = applicationMapper.selectOne(
                Wrappers.<OnboardingApplication>lambdaQuery()
                        .eq(OnboardingApplication::getApplicantUserId, userId)
                        .orderByDesc(OnboardingApplication::getId)
                        .last("LIMIT 1"));
        if (existing == null) {
            return;
        }
        throw BusinessException.conflict(existing.isPending()
                ? "已有一份待审核的入驻申请，请等待审核结果"
                : "已有一份被驳回的入驻申请，请修改后重新提交（不要新建一份）");
    }

    /** 只有待审核的申请能被审核。已通过/已驳回的重复操作**不当作幂等成功**：每次审核都往
     *  append-only 的流水里追加一条结论，静默成功会写出两条互相矛盾的结论。 */
    private void requirePending(OnboardingApplication application) {
        if (!application.isPending()) {
            throw BusinessException.conflict(application.isRejected() ? "该申请已被驳回，无需重复审核" : "该申请已审核通过");
        }
    }

    private OnboardingApplication requireMine(long applicationId, long userId) {
        OnboardingApplication application = applicationMapper.selectOne(
                Wrappers.<OnboardingApplication>lambdaQuery()
                        .eq(OnboardingApplication::getId, applicationId)
                        .eq(OnboardingApplication::getApplicantUserId, userId));
        if (application == null) {
            // 别人的申请按不存在处理：404 不泄露「这个 id 存在」（docs/conventions.md）
            throw BusinessException.notFound();
        }
        return application;
    }

    private OnboardingApplication requireApplication(long applicationId) {
        OnboardingApplication application = applicationMapper.selectById(applicationId);
        if (application == null) {
            throw BusinessException.notFound();
        }
        return application;
    }

    private OnboardingApplicationView detail(OnboardingApplication application, Provider provider) {
        List<ProviderReviewLog> logs = reviewLogMapper.selectList(Wrappers.<ProviderReviewLog>lambdaQuery()
                .eq(ProviderReviewLog::getTargetType, ProviderReviewLog.TARGET_ONBOARDING)
                .eq(ProviderReviewLog::getTargetId, application.getId())
                .orderByAsc(ProviderReviewLog::getId));
        return ProviderViews.toDetail(application, provider, access.qualificationsOf(provider.getId()), logs, cipher);
    }

    /**
     * 用户没填材料名时的兜底名：类型名由 {@link ProviderQualification#typeName(Integer)} 给，
     * 认不出的类型兜「资质材料」（宁可笼统，也不要在这里凭空编一个像真的名字）。
     */
    private static String defaultQualificationName(Integer type) {
        String name = ProviderQualification.typeName(type);
        return name == null ? "资质材料" : name;
    }
}
