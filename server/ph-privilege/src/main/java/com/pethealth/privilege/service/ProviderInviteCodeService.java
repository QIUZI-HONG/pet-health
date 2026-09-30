package com.pethealth.privilege.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pethealth.api.provider.ProviderAccessApi;
import com.pethealth.api.provider.ProviderInviteCodeView;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.security.LoginDomain;
import com.pethealth.privilege.domain.InviteRelation;
import com.pethealth.privilege.domain.ProviderInviteCode;
import com.pethealth.privilege.mapper.InviteRelationMapper;
import com.pethealth.privilege.mapper.ProviderInviteCodeMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 门店推广码：一店一码、可查战况、供归因使用。契约见 contract/provider.yaml 的
 * {@code /invite-code}。
 *
 * <h2>它为什么在这里（ph-privilege 而不是 ph-provider）</h2>
 *
 * <p>码本身只是「门店 id + 一串字符」，但它的**唯一用途是产生邀请关系**，而邀请关系是
 * ph-privilege 的表。把码放在 ph-provider 会让归因那一刻要跨模块插入 ph-privilege 的表
 * （ADR-0006 禁止）。所以码跟关系放在一起，门店身份通过 {@link ProviderAccessApi} 只读拿到。
 *
 * <h2>三条口径</h2>
 *
 * <ul>
 *   <li><b>一店一码，创建幂等</b>：取码是 POST（会写库），重复调用返回同一个码。
 *       GET 不写库：没生成过就返回一个 {@code code = null} 的视图，前端据此显示「生成」按钮
 *       ——一个会写库的 GET 是最容易被误触的接口形态；
 *   <li><b>只有门店管理员能取码</b>：它是门店的对外物料，技师不能替门店承诺拉新
 *       （与券贡献同一道门禁，ADR-0037 的权限矩阵）；
 *   <li><b>码与门店的绑定不随审核状态变化</b>：门店被冻结后码仍然存在（历史关系还要能解释），
 *       归因在写入那一刻不校验门店状态——那属于订单与浏览侧的事，不在拉新归因里再加一道。
 * </ul>
 */
@Service
public class ProviderInviteCodeService {

    /** 码长度：前缀 2 位 + 8 位随机（用户邀请码是 8 位，两者长度不同，查找不会互撞）。 */
    private static final int RANDOM_LENGTH = 8;
    /** 与用户邀请码同一套字符集：去掉易混的 0/1/I/L/O。 */
    private static final char[] ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ProviderInviteCodeMapper codeMapper;
    private final InviteRelationMapper relationMapper;
    private final ObjectProvider<ProviderAccessApi> providerAccess;

    public ProviderInviteCodeService(ProviderInviteCodeMapper codeMapper,
                                     InviteRelationMapper relationMapper,
                                     ObjectProvider<ProviderAccessApi> providerAccess) {
        this.codeMapper = codeMapper;
        this.relationMapper = relationMapper;
        this.providerAccess = providerAccess;
    }

    /** 我的推广码与战况（服务者后台）。没生成过时 {@code code = null}，不写库。 */
    @Transactional(readOnly = true)
    public ProviderInviteCodeView view() {
        long providerId = requireAdminProviderId();
        ProviderInviteCode code = findByProvider(providerId);
        return viewOf(providerId, code);
    }

    /** 生成（或取回已有的）推广码。**幂等**：重复调用返回同一个码，不会换码。 */
    @Transactional
    public ProviderInviteCodeView ensure() {
        long providerId = requireAdminProviderId();
        ProviderInviteCode existing = findByProvider(providerId);
        if (existing != null) {
            return viewOf(providerId, existing);
        }
        ProviderInviteCode created = new ProviderInviteCode();
        created.setProviderId(providerId);
        created.setStatus(ProviderInviteCode.STATUS_ENABLED);
        for (int attempt = 0; attempt < 5; attempt++) {
            created.setCode(randomCode());
            try {
                codeMapper.insert(created);
                return viewOf(providerId, created);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 一店一码由唯一键兜底：并发取码时回读那一条，而不是报错
                ProviderInviteCode concurrent = findByProvider(providerId);
                if (concurrent != null) {
                    return viewOf(providerId, concurrent);
                }
            }
        }
        throw BusinessException.conflict("推广码生成失败，请重试");
    }

    // ---------------------------------------------------------------- 归因侧

    /**
     * 按码找门店归属。**只给归因用**：{@code InviteService.attribute} 在用户码查不到时调它。
     *
     * <p>停用的码仍会被找到（返回实体），由调用方决定是否拒绝——这里不掺业务判断。
     */
    @Transactional(readOnly = true)
    public Optional<ProviderInviteCode> findByCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(codeMapper.selectOne(Wrappers.<ProviderInviteCode>lambdaQuery()
                .eq(ProviderInviteCode::getCode, code.trim().toUpperCase(java.util.Locale.ROOT))));
    }

    // ---------------------------------------------------------------- 内部

    private ProviderInviteCode findByProvider(long providerId) {
        return codeMapper.selectOne(Wrappers.<ProviderInviteCode>lambdaQuery()
                .eq(ProviderInviteCode::getProviderId, providerId));
    }

    /** 服务者后台的门禁：登录域必须是 provider，且当前账号是**某家店的管理员**。 */
    private long requireAdminProviderId() {
        CurrentUser.requireDomain(LoginDomain.PROVIDER);
        ProviderAccessApi api = providerAccess.getIfAvailable();
        if (api == null) {
            throw BusinessException.conflict("服务者身份服务当前不可用，请稍后重试");
        }
        long userId = CurrentUser.userId();
        long providerId = api.findProviderId(userId)
                .orElseThrow(() -> BusinessException.notFound("当前账号还没有绑定的门店"));
        if (!api.isAdmin(userId)) {
            throw BusinessException.notFound("当前账号不是该门店的管理员");
        }
        return providerId;
    }

    private ProviderInviteCodeView viewOf(long providerId, ProviderInviteCode code) {
        return new ProviderInviteCodeView(
                code == null ? null : code.getCode(),
                code == null ? null : code.getStatus(),
                code == null ? null : code.getCreatedAt(),
                count(providerId, InviteRelation.STATUS_EFFECTIVE),
                count(providerId, InviteRelation.STATUS_PENDING),
                count(providerId, InviteRelation.STATUS_INVALID));
    }

    private long count(long providerId, int status) {
        Long total = relationMapper.selectCount(Wrappers.<InviteRelation>lambdaQuery()
                .eq(InviteRelation::getInviterProviderId, providerId)
                .eq(InviteRelation::getStatus, status));
        return total == null ? 0L : total;
    }

    private static String randomCode() {
        StringBuilder sb = new StringBuilder(ProviderInviteCode.CODE_PREFIX);
        for (int i = 0; i < RANDOM_LENGTH; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    /** 归因时要记的时间（与用户邀请码同一条口径：归因时间 = 注册那一刻）。 */
    static LocalDateTime now() {
        return com.pethealth.common.time.AppTime.now();
    }
}
