package com.pethealth.api.provider;

import jakarta.validation.constraints.NotBlank;

/**
 * 改价，对应 contract 的 {@code ServicePriceRequest}。
 *
 * <p>改价会把服务项**打回待审核**：价格是对外承诺，改了要有人再看一眼（交付文档 2.2 把
 * 「审核服务」给了平台运营）。已上架的服务项改价后立即从前台撤下，直到再审通过——
 * 「一边展示新价一边等审核」是不可能成立的中间态。
 */
public record ServicePriceRequest(

        @NotBlank(message = "价格不能为空")
        String price) {
}
