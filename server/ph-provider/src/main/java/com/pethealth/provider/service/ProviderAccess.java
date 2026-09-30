package com.pethealth.provider.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.error.ErrorCode;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.security.LoginDomain;
import com.pethealth.provider.domain.OnboardingApplication;
import com.pethealth.provider.domain.Provider;
import com.pethealth.provider.domain.ProviderQualification;
import com.pethealth.provider.domain.ProviderUser;
import com.pethealth.provider.mapper.OnboardingApplicationMapper;
import com.pethealth.provider.mapper.ProviderMapper;
import com.pethealth.provider.mapper.ProviderQualificationMapper;
import com.pethealth.provider.mapper.ProviderUserMapper;
import com.pethealth.common.time.AppTime;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 「当前登录的服务者后台身份是谁、他能做什么」——本模块所有服务者侧接口的门禁都走这里。
 *
 * <p>三道判定按顺序，各自回不同的码：
 *
 * <ol>
 *   <li><b>有没有绑定服务者</b>：没绑定（还没通过审核）→ 40400。用 404 而不是 403：
 *       对这个账号来说「我的门店」这个资源确实还不存在。
 *   <li><b>是不是管理员</b>：技师（{@code role=2}）没有选品定价与门店维护的权限，
 *       而这些动作是「管理员」的（技师只是他的权限子集）→ 40300。
 *   <li><b>服务者状态允许不允许</b>：待审核 / 驳回 / 冻结都不能经营 → 40300，且消息里说清是哪一种，
 *       否则服务者只会看到「无权限」而不知道要做什么。
 * </ol>
 *
 * <p>另外提供**上架门禁** {@link #requireValidQualification}：资质全部过期后自动下架的同时，
 * 也要挡住「自己再上架」——两处都判才是硬的。
 */
@Component
public class ProviderAccess {

    private final ProviderUserMapper providerUserMapper;
    private final ProviderMapper providerMapper;
    private final ProviderQualificationMapper qualificationMapper;
    private final OnboardingApplicationMapper applicationMapper;

    public ProviderAccess(ProviderUserMapper providerUserMapper, ProviderMapper providerMapper,
                          ProviderQualificationMapper qualificationMapper,
                          OnboardingApplicationMapper applicationMapper) {
        this.providerUserMapper = providerUserMapper;
        this.providerMapper = providerMapper;
        this.qualificationMapper = qualificationMapper;
        this.applicationMapper = applicationMapper;
    }

    /** 当前登录的服务者后台账号 id（非 provider 域一律 40100）。 */
    public long currentUserId() {
        return CurrentUser.requireDomain(LoginDomain.PROVIDER);
    }

    /**
     * 当前身份绑定的服务者；没绑定返回 {@code null}（读「我的申请」这类接口不需要绑定）。
     *
     * <p>只认启用中的绑定：停用绑定（{@code status=0}）与没绑定是同一件事——这个人现在不归属于
     * 任何服务者。多条绑定时取最早的一条，避免「一个人挂两家店」这种还没定规则的情况
     * 产生随机结果（那属于 #78 的待澄清，见 ADR-0035）。
     */
    public Provider findBound(long userId) {
        List<ProviderUser> bindings = providerUserMapper.selectList(Wrappers.<ProviderUser>lambdaQuery()
                .eq(ProviderUser::getUserId, userId)
                .eq(ProviderUser::getStatus, ProviderUser.STATUS_ACTIVE)
                .orderByAsc(ProviderUser::getId));
        if (bindings.isEmpty()) {
            return null;
        }
        return providerMapper.selectById(bindings.get(0).getProviderId());
    }

    /** 绑定的服务者，没绑定直接 40400。给「读自己的门店」用。 */
    public Provider requireBound(long userId) {
        Provider provider = findBound(userId);
        if (provider == null) {
            throw BusinessException.notFound("当前账号还没有关联的服务者");
        }
        return provider;
    }

    /**
     * 按 id 取服务者，不存在直接 40400（运营侧按 id 操作的入口用）。
     *
     * <p>为什么收在这里：这段「查不到就是 40400」原先在入驻审核与运营处置里各写了一份，
     * 而它**是一条对外口径**（「不存在的服务者」与「不能看的服务者」对调用方是同一个结果），
     * 两份实现意味着这条口径可以悄悄分叉。
     */
    public Provider requireById(long providerId) {
        Provider provider = providerMapper.selectById(providerId);
        if (provider == null) {
            throw BusinessException.notFound();
        }
        return provider;
    }

    /** 绑定 + 管理员角色；两者任一不满足都拒绝。 */
    public Provider requireAdmin(long userId) {
        ProviderUser binding = providerUserMapper.selectOne(Wrappers.<ProviderUser>lambdaQuery()
                .eq(ProviderUser::getUserId, userId)
                .eq(ProviderUser::getStatus, ProviderUser.STATUS_ACTIVE)
                .orderByAsc(ProviderUser::getId)
                .last("LIMIT 1"));
        if (binding == null) {
            // 还没绑定（没申请过 / 审核中 / 已驳回）——按申请状态给出能看懂的话，
            // 而不是笼统的「没有服务者」：这三种情况服务者要做的事完全不同
            throw notBoundYet(userId);
        }
        if (binding.getRole() == null || binding.getRole() != ProviderUser.ROLE_ADMIN) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "该操作只对服务者管理员开放");
        }
        Provider provider = providerMapper.selectById(binding.getProviderId());
        if (provider == null) {
            throw BusinessException.notFound("当前账号还没有关联的服务者");
        }
        return provider;
    }

    /**
     * 「还没绑定」时按申请单的状态分情况回答。
     *
     * <ul>
     *   <li>没有申请过 → 40400（对这个账号来说，门店这个资源确实还不存在）；
     *   <li>审核中 / 已驳回 → 40300，消息里说清是哪一种。<b>不选 404</b>：
     *       服务者明明提交过材料，回「不存在」会让人以为材料丢了。
     * </ul>
     */
    private BusinessException notBoundYet(long userId) {
        OnboardingApplication latest = applicationMapper.selectOne(
                Wrappers.<OnboardingApplication>lambdaQuery()
                        .eq(OnboardingApplication::getApplicantUserId, userId)
                        .orderByDesc(OnboardingApplication::getId)
                        .last("LIMIT 1"));
        if (latest == null) {
            return BusinessException.notFound("当前账号还没有关联的服务者");
        }
        if (latest.isPending()) {
            return new BusinessException(ErrorCode.FORBIDDEN, "入驻申请审核中，暂不能操作");
        }
        return new BusinessException(ErrorCode.FORBIDDEN, "入驻申请已被驳回，请修改后重新提交");
    }

    /** 绑定 + 管理员 + 服务者状态正常——经营类动作（选品、定价、上架、维护门店）的入口。 */
    public Provider requireActiveAdmin(long userId) {
        Provider provider = requireAdmin(userId);
        if (!provider.isApproved()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, statusMessage(provider.getStatus()));
        }
        return provider;
    }

    /**
     * 上架门禁：至少要有一份**未过期**的资质材料。
     *
     * <p>没有到期日的材料算长期有效。全部过期时 {@code validUntil < 今天}，
     * 此时既不能新上架也不能补上架——补交材料（{@code PUT /provider/qualifications}）后即可恢复。
     */
    public void requireValidQualification(long providerId) {
        if (!hasValidQualification(providerId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "资质已过期，请先补交材料再上架");
        }
    }

    public boolean hasValidQualification(long providerId) {
        return hasValidQualification(qualificationsOf(providerId));
    }

    /**
     * 同上，但材料清单**已经取出来了**。
     *
     * <p>给「一次请求里既要判可见性、又要拼资质摘要」的调用方用（C 端服务者详情页）：
     * 那种场景下再查一遍库纯属浪费，而判定规则又必须只有一份——所以规则在这里、材料由调用方给。
     * 判定逐字等于 {@code anyMatch(countsAsValid)}：被驳回的不算、过期的不算。
     */
    public boolean hasValidQualification(List<ProviderQualification> qualifications) {
        if (qualifications.isEmpty()) {
            return false;
        }
        java.time.LocalDate today = AppTime.today();
        return qualifications.stream().anyMatch(qualification -> qualification.countsAsValid(today));
    }

    public List<ProviderQualification> qualificationsOf(long providerId) {
        return qualificationMapper.selectList(Wrappers.<ProviderQualification>lambdaQuery()
                .eq(ProviderQualification::getProviderId, providerId)
                .orderByAsc(ProviderQualification::getId));
    }

    /** 审核通过时把申请人绑定为管理员（唯一键兜底：重复绑定不会产生第二条记录）。 */
    public void bindAdmin(long providerId, long userId) {
        ProviderUser existing = providerUserMapper.selectOne(Wrappers.<ProviderUser>lambdaQuery()
                .eq(ProviderUser::getProviderId, providerId)
                .eq(ProviderUser::getUserId, userId));
        if (existing != null) {
            existing.setRole(ProviderUser.ROLE_ADMIN);
            existing.setStatus(ProviderUser.STATUS_ACTIVE);
            providerUserMapper.updateById(existing);
            return;
        }
        ProviderUser binding = new ProviderUser();
        binding.setProviderId(providerId);
        binding.setUserId(userId);
        binding.setRole(ProviderUser.ROLE_ADMIN);
        binding.setStatus(ProviderUser.STATUS_ACTIVE);
        providerUserMapper.insert(binding);
    }

    /** 状态 → 给服务者看的一句话。别只说「无权限」——他会不知道该做什么。 */
    private static String statusMessage(Integer status) {
        if (status == null) {
            return "服务者状态异常，请联系平台";
        }
        return switch (status) {
            case Provider.STATUS_PENDING -> "入驻申请审核中，暂不能操作";
            case Provider.STATUS_REJECTED -> "入驻申请已被驳回，请修改后重新提交";
            case Provider.STATUS_FROZEN -> "服务者已被冻结，暂不能操作";
            default -> "服务者状态异常，请联系平台";
        };
    }
}
