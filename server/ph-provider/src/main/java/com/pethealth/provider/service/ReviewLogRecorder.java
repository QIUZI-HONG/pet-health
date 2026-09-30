package com.pethealth.provider.service;

import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.trace.TraceIds;
import com.pethealth.provider.domain.ProviderReviewLog;
import com.pethealth.provider.mapper.ProviderReviewLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审核流水的写入（append-only）。
 *
 * <p>单独一个类而不是在每个 service 里 new 一个实体：三类审核（入驻 / 上架 / 提案）都要写同一张表，
 * 「谁、何时、结论、理由」的取法只该有一份——尤其是 {@code actorDomain}，
 * 它区分「服务者提交」与「平台审核」，取错了流水就失去全部意义。
 *
 * <p>{@code actorId} 取自 {@link TraceIds}（鉴权过滤器把登录身份放进去了），
 * 系统写入（定时任务）是 0；{@code actorDomain} 在没有登录身份时留空。
 */
@Service
public class ReviewLogRecorder {

    private final ProviderReviewLogMapper mapper;

    public ReviewLogRecorder(ProviderReviewLogMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 记一条审核流水。
     *
     * @param remark 驳回原因或审核意见，可为空；**驳回时必填**（理由缺失的驳回等于没驳回）
     */
    @Transactional
    public void record(int targetType, long targetId, long providerId, int action, String remark) {
        ProviderReviewLog log = new ProviderReviewLog();
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setProviderId(providerId);
        log.setAction(action);
        log.setActorId(TraceIds.currentOperatorId());
        log.setActorDomain(currentDomain());
        log.setRemark(remark == null || remark.isBlank() ? null : remark.trim());
        mapper.insert(log);
    }

    /** 当前登录域的小写名（provider / admin）；未登录（定时任务）留空。 */
    private static String currentDomain() {
        if (!CurrentUser.isLoggedIn()) {
            return "";
        }
        return CurrentUser.get().domain().name().toLowerCase(java.util.Locale.ROOT);
    }
}
