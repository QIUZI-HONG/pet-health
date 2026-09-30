package com.pethealth.record.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 档案模块对外暴露的「宠物快照」接口，供 ph-ai 组装 AI 服务的请求上下文。
 *
 * <p>为什么不让 ph-ai 直接查 pet 表：ADR-0006 禁止跨模块碰表。快照的字段刻意只放
 * **分级真正会用到的**——少一个字段就少一处耦合（`recent_records` 暂不提供，见 #101）。
 *
 * <p>专项照护的三个字段（{@code careMode} / {@code ageYears} / {@code ageText}）是切片 #116
 * 加的：交付文档 F009 要求「AI 咨询的上下文带上专项信息」，而这是本模块能提供该信息的唯一出口。
 * **模型调用仍在 `ai/`**（ADR-0009），这里只负责把事实交出去。
 */
public interface AiPetApi {

    /** 宠物快照。{@code species} 1 犬 / 2 猫；体重、慢病、年龄可空。 */
    record PetSnapshot(
            long petId,
            long userId,
            int species,
            String breed,
            LocalDate birthDate,
            BigDecimal weight,
            String chronicDesc,
            /** 是否处于专项照护模式（派生态，判定见 ADR-0032；用户手动关闭后为 false） */
            boolean careMode,
            /** 实足年龄；没填生日为 null */
            Integer ageYears,
            /** 年龄的可读文案（「9 岁 3 个月」）；没填生日为 null */
            String ageText) {
    }

    /**
     * 取一只宠物的快照；**不存在、已删除或不属于该用户时返回空**。
     *
     * <p>调用方把空当作「资源不存在」（40400）处理，不区分三种情况（docs/conventions.md 的越权口径）。
     */
    java.util.Optional<PetSnapshot> snapshotOwnedBy(long userId, long petId);
}
