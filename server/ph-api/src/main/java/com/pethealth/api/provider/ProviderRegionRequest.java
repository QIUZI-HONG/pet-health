package com.pethealth.api.provider;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 指定服务者的区域编码，对应 contract 的 {@code ProviderRegionRequest}。
 *
 * <p>{@code regionCode} **可为 null**：清空区域是合法动作（运营把门店从一个片区摘下来）。
 * 所以这里不能加 {@code @NotBlank}——那是「必填」，而这是「可空但一旦给了就要合法」。
 *
 * <p>取值格式限制成大写字母 / 数字 / 连字符（如 {@code SH-XH}）：区域编码是运营侧的分类，
 * 不是自由文本，格式放开会让「同一个区出现 SH-XH / sh_xh / 徐汇」三份，筛选就废了。
 * 具体编码表（谁定、怎么维护）仍是留白，见 V45 的注释。
 */
public record ProviderRegionRequest(

        @Size(max = 32, message = "区域编码最长 32 个字符")
        @Pattern(regexp = "^[A-Z0-9-]*$", message = "区域编码只能用大写字母、数字与连字符（如 SH-XH），留空表示清空")
        String regionCode) {
}
