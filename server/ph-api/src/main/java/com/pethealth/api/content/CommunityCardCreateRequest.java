package com.pethealth.api.content;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 一键生成经验卡片，对应 contract/app.yaml 的 {@code CommunityCardCreateRequest}。
 *
 * <p>「一键生成」是**编辑动作**，不是导出动作：正文由前端从那条打卡 / 就医记录预填，
 * 用户改完再提交。服务端只做两件事——校验宠物归属、把卡片与来源记录关联起来落库
 * （{@code petId} + {@code sourceType} + {@code sourceRef}），**不重读档案正文**：
 * 档案模块没有「按 id 取一条记录」的对外接口（ADR-0006 不许 join 它的表），
 * 而为一个展示用的关联去扩它的接口不在本切片范围内（ADR-0051 待澄清第 2 条）。
 *
 * <p>{@code species} / {@code breed} / {@code diseaseTag} 是**快照标签**（「同品种 / 同病」聚合用）：
 * 随卡片落库之后，宠物改名、品种登记被纠正都不会改写已经发出去的历史卡片——
 * 与订单快照同一口径（V29 的注释）。
 *
 * @param petId       来源记录所属的宠物；服务端校验它属于当前用户（不属于我 → 40400）
 * @param sourceType  来源记录类型：1 打卡 / 2 就医记录
 * @param sourceRef   来源记录 id（打卡记录 / 档案记录的 id），只做关联与追溯
 * @param title       卡片标题
 * @param content     卡片正文（提交后进审核：待审 → 机审命中即被拒 / 运营通过）
 * @param species     物种快照：1 犬 / 2 猫；不传表示不参与物种聚合
 * @param breed       品种快照，如「柯基」
 * @param diseaseTag  慢病标签快照，如「慢性肾病」
 */
public record CommunityCardCreateRequest(

        @NotNull(message = "宠物不能为空")
        Long petId,

        @NotNull(message = "来源记录类型不能为空（1 打卡 / 2 就医记录）")
        Integer sourceType,

        @NotBlank(message = "来源记录不能为空")
        @Size(max = 64, message = "来源记录 id 最长 64 个字符")
        String sourceRef,

        @NotBlank(message = "标题不能为空")
        @Size(max = 64, message = "标题最长 64 个字符")
        String title,

        @NotBlank(message = "正文不能为空")
        @Size(max = 2000, message = "正文最长 2000 个字符")
        String content,

        Integer species,

        @Size(max = 32, message = "品种最长 32 个字符")
        String breed,

        @Size(max = 32, message = "慢病标签最长 32 个字符")
        String diseaseTag) {
}
