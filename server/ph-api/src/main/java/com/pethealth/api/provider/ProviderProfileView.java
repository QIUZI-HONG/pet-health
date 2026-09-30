package com.pethealth.api.provider;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 服务者（门店）信息，对应 contract 的 {@code ProviderProfileView}。
 *
 * <p>{@code phone} 是脱敏值（ADR-0013）。{@code status} 是服务者的经营状态
 * （0 待审核 / 1 正常 / 2 驳回 / 3 冻结）——**读接口在非正常态也返回门店信息**，
 * 这样服务者能看见「审核中」而不是收到一个 404；写接口才按状态拒绝。
 *
 * <p>{@code category} 是联盟分类维度取值（{@code provider_alliance_category.id}，V43 起可维护），
 * {@code categoryName} 是它的名称。两个都给：值用于写回与比较，名称用于显示——
 * 名称让三端不必各持一份标签表（那正是「维度可维护」要拆掉的东西）。
 * 维度查不到时 {@code categoryName} 为 {@code null}（数据或代码有一处旧了，不静默顶替）。
 *
 * <p>{@code level} / {@code monthlyScore} / {@code rating} 本期不参与任何计算
 * （考核 #58、评价体系未落地），字段先给出，避免将来改表。
 *
 * <p>{@code recommendPriority}（V45 起）是「等级 → 优先级」档位映射的落点，**它才是 C 端找店排序用的那一列**
 * （{@code level} 只是三档标签，档位映射改了它不变）。{@code regionCode} 是运营维护的区域编码，
 * 现在只用于筛选，**排他性的「区域保护」仍未定**。
 */
public record ProviderProfileView(
        Long id,
        String name,
        Integer type,
        Integer category,
        String categoryName,
        String logo,
        String intro,
        String address,
        String lng,
        String lat,
        String phone,
        List<BusinessHour> businessHours,
        Integer status,
        Integer level,
        Integer recommendPriority,
        String regionCode,
        String monthlyScore,
        String rating,
        LocalDateTime approvedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
