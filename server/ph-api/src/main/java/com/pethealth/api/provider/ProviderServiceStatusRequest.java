package com.pethealth.api.provider;

import jakarta.validation.constraints.NotNull;

/**
 * 上架 / 下架，对应 contract 的 {@code ProviderServiceStatusRequest}。
 *
 * <p>{@code status} 只接受 1（上架）与 2（下架）——**0（待审核）与 3（已驳回）不能由服务者设**，
 * 那是审核流程的结果。上架的前提是当前状态为「已下架」（即审核已通过），
 * 未审核通过就想上架一律被拦（40300，验收项之一）。
 */
public record ProviderServiceStatusRequest(

        @NotNull(message = "状态不能为空")
        Integer status) {
}
