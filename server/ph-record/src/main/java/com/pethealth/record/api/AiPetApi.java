package com.pethealth.record.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 档案模块对外暴露的「宠物快照」接口，供 ph-ai 组装 AI 服务的请求上下文。
 *
 * <p>为什么不让 ph-ai 直接查 pet 表：ADR-0006 禁止跨模块碰表。快照的字段刻意只放
 * **分级真正会用到的**——少一个字段就少一处耦合（`recent_records` 暂不提供，见 #101）。
 */
public interface AiPetApi {

    /** 宠物快照。{@code species} 1 犬 / 2 猫；体重与慢病可空。 */
    record PetSnapshot(
            long petId,
            long userId,
            int species,
            String breed,
            LocalDate birthDate,
            BigDecimal weight,
            String chronicDesc) {
    }

    /**
     * 取一只宠物的快照；**不存在、已删除或不属于该用户时返回空**。
     *
     * <p>调用方把空当作「资源不存在」（40400）处理，不区分三种情况（docs/conventions.md 的越权口径）。
     */
    java.util.Optional<PetSnapshot> snapshotOwnedBy(long userId, long petId);
}
