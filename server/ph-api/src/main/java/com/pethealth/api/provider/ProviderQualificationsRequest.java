package com.pethealth.api.provider;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 补交资质材料，对应 contract 的 {@code ProviderQualificationsRequest}。
 *
 * <p>**整体替换**（与营业时间同一口径）：资质是「当前有效的那一版」，
 * 逐条增删会让上架门禁（还有没有一份没过期的）取决于调用顺序。
 *
 * <p>为什么单独一个包装对象而不是直接收数组：契约里请求体永远是对象——
 * 数组顶层没有地方挂将来可能出现的字段（如「补交说明」），而这类字段迟早会出现。
 */
public record ProviderQualificationsRequest(

        @NotEmpty(message = "至少提交一份资质材料")
        @Size(max = 20, message = "一次最多提交 20 份材料")
        @Valid
        List<ProviderQualificationRequest> qualifications) {
}
