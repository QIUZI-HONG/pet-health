package com.pethealth.api.provider;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 从标准目录勾选服务项并定价，对应 contract 的 {@code ProviderServiceRequest}。
 *
 * <p>{@code price} 用字符串收（"128.00"），解析与两位小数校验在 ph-catalog 的 {@code Price} 里做一次。
 * **区间校验不在这个 DTO 上**：区间在目录侧（会随运营调整），注解里写不了，
 * 由 ph-provider 调 ph-catalog 的定价接口校验，越界回 90001 并附上区间文案（交付文档 2.4 / 2.5）。
 *
 * <p>一（服务者 × 目录项）只能有一条：重复勾选会命中唯一键，接口按 40900 回
 * 「该服务项已在你的服务列表里」——**不是**静默改价，改价有独立接口，两者混起来
 * 会让一次误点的提交悄悄改掉线上价格。
 */
public record ProviderServiceRequest(

        @NotBlank(message = "目录项编码不能为空")
        @Pattern(regexp = "^[A-Z]{2}-\\d{3}$", message = "目录项编码形如 HE-001：两位大写字母 + 三位数字")
        String serviceCode,

        @NotBlank(message = "价格不能为空")
        String price) {
}
