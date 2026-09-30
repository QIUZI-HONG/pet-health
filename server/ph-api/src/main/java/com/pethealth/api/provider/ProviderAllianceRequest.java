package com.pethealth.api.provider;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 改一个服务者的联盟归属，对应 contract 的 {@code ProviderAllianceRequest}。
 *
 * <p>{@code category} 是 {@code provider_alliance_category.id}，**必须是一档启用中的维度**——
 * 值域不再由代码里的 {@code @Min/@Max} 钉死（那正是本次改造要拆掉的东西），
 * 而是在服务层查表校验：不存在的维度与停用的维度都拒绝（40001）。
 */
public record ProviderAllianceRequest(

        @NotNull(message = "联盟分类不能为空")
        @Min(value = 1, message = "联盟分类必须是有效的维度取值")
        Integer category) {
}
