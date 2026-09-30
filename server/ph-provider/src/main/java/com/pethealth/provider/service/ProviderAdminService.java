package com.pethealth.provider.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pethealth.api.admin.ProviderStatusRequest;
import com.pethealth.api.provider.ProviderProfileView;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.crypto.FieldCipher;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.util.Text;
import com.pethealth.provider.domain.Provider;
import com.pethealth.provider.domain.ProviderReviewLog;
import com.pethealth.provider.mapper.ProviderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运营侧的服务者视图与经营状态处置，契约见 contract/admin.yaml 的 {@code /providers}。
 *
 * <p><b>冻结就是清退的落点</b>（2026-09-29 口径）：清退 = 冻结接单（禁止新订单与新上架）
 * + 保留历史订单与档案 + 已预约订单转「取消」（不是退款——ADR-0036 定了平台无资金流，
 * 退款退化为线下动作）。本切片只做「冻结 / 解冻」这一半：
 *
 * <ul>
 *   <li><b>禁止新上架</b>：冻结状态过不了 {@link ProviderAccess#requireActiveAdmin}；
 *   <li><b>已上架的服务项不自动下架</b>：C 端浏览与下单必须按服务者状态过滤（那是订单侧的事，
 *       接口留白见 ADR-0035）。不在这里下架的理由是「冻结」与「下架」是两个动作，
 *       把两者焊死会让解冻时也恢复不了原状；
 *   <li><b>已预约订单转取消</b>：属于订单模块（#77），本切片不做。
 * </ul>
 *
 * <p>权限口径：交付文档 2.2 把「审核商家」（文档用词）给平台运营，而 ADR-0037 把**清退给超级管理员**。
 * 本切片判别不出这两者——后台账号与角色体系还没落地，token 里只有登录域（见 ADR-0035 的
 * 「需要协调」）。**这不是这里忘了判角色**，而是现在没有判的依据。
 */
@Service
public class ProviderAdminService {

    private final ProviderMapper providerMapper;
    private final ProviderAccess access;
    private final ReviewLogRecorder reviewLog;
    private final FieldCipher cipher;

    public ProviderAdminService(ProviderMapper providerMapper, ProviderAccess access,
                                ReviewLogRecorder reviewLog, FieldCipher cipher) {
        this.providerMapper = providerMapper;
        this.access = access;
        this.reviewLog = reviewLog;
        this.cipher = cipher;
    }

    /**
     * 服务者列表。
     *
     * <p>{@code keyword} 只搜名称：电话是密文，模糊搜索在 ADR-0013 里就被明确排除了
     * （密文没有保序性），要按电话找人得先有服务者 id。
     */
    @Transactional(readOnly = true)
    public PageResult<ProviderProfileView> list(Integer status, Integer type, String keyword,
                                                long page, long pageSize) {
        CurrentUser.requireAdmin();
        String nameKeyword = Text.trimToNull(keyword);
        Page<Provider> result = providerMapper.selectPage(new Page<>(page, pageSize),
                Wrappers.<Provider>lambdaQuery()
                        .eq(status != null, Provider::getStatus, status)
                        .eq(type != null, Provider::getType, type)
                        .like(nameKeyword != null, Provider::getName, nameKeyword)
                        .orderByDesc(Provider::getCreatedAt)
                        .orderByDesc(Provider::getId));
        return PageResult.from(result, provider -> ProviderViews.toProfileView(provider, cipher));
    }

    @Transactional(readOnly = true)
    public ProviderProfileView get(long providerId) {
        CurrentUser.requireAdmin();
        return ProviderViews.toProfileView(access.requireById(providerId), cipher);
    }

    /**
     * 冻结（3）/ 解冻（1）。
     *
     * <p>被驳回（2）的服务者不能从这里恢复为正常：那条路必须走申请单的重新提交与审核，
     * 否则一次驳回就等于白拒——「被清退的记录不占唯一性名额」这条规则也建立在
     * 「驳回与冻结必须经由审核流程复位」之上。
     */
    @Transactional
    public ProviderProfileView changeStatus(long providerId, ProviderStatusRequest request) {
        CurrentUser.requireAdmin();
        Provider provider = access.requireById(providerId);
        int target = request.status() == null ? -1 : request.status();
        int current = provider.getStatus() == null ? -1 : provider.getStatus();

        if (target == Provider.STATUS_FROZEN) {
            if (current == Provider.STATUS_FROZEN) {
                throw BusinessException.conflict("该服务者已是冻结状态");
            }
            provider.setStatus(Provider.STATUS_FROZEN);
            providerMapper.updateById(provider);
            reviewLog.record(ProviderReviewLog.TARGET_PROVIDER, provider.getId(), provider.getId(),
                    ProviderReviewLog.ACTION_FREEZE, Text.trimToNull(request.reason()));
            return ProviderViews.toProfileView(provider, cipher);
        }
        if (target == Provider.STATUS_APPROVED) {
            if (current != Provider.STATUS_FROZEN) {
                throw BusinessException.conflict(current == Provider.STATUS_REJECTED
                        ? "该服务者是被驳回的，需重新提交入驻申请并审核通过后才能恢复"
                        : "该服务者当前不是冻结状态，无需解冻");
            }
            provider.setStatus(Provider.STATUS_APPROVED);
            providerMapper.updateById(provider);
            reviewLog.record(ProviderReviewLog.TARGET_PROVIDER, provider.getId(), provider.getId(),
                    ProviderReviewLog.ACTION_UNFREEZE, null);
            return ProviderViews.toProfileView(provider, cipher);
        }
        throw BusinessException.paramInvalid("状态只能是 1（正常）或 3（冻结）");
    }
}
