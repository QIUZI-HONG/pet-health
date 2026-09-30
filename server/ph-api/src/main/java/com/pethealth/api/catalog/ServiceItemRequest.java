package com.pethealth.api.catalog;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新建 / 修改目录项，对应 contract 的 {@code ServiceItemRequest}。
 *
 * <p>价格区间用**字符串**收（"88.00"），解析与两位小数校验在 ph-catalog 的 {@code Price} 值对象里
 * 做一次——不在注解里再写一遍正则，改规则时只有一处要改（与体重走 {@code Weight} 同一取舍）。
 *
 * <p>{@code priceMin &lt;= priceMax} 是跨字段规则：注解表达不了，由 service 校验并回 40001。
 */
public record ServiceItemRequest(

        @NotBlank(message = "目录项编码不能为空")
        @Pattern(regexp = "^[A-Z]{2}-\\d{3}$", message = "目录项编码形如 HE-001：两位大写字母 + 三位数字")
        String code,

        @NotBlank(message = "所属分类不能为空")
        @Size(max = 32, message = "分类编码最长 32 个字符")
        String categoryCode,

        @NotBlank(message = "项目名称不能为空")
        @Size(max = 128, message = "项目名称最长 128 个字符")
        String name,

        @NotBlank(message = "价格区间下限不能为空")
        String priceMin,

        @NotBlank(message = "价格区间上限不能为空")
        String priceMax,

        @Size(max = 16, message = "计价单位最长 16 个字符")
        String priceUnit,

        @Min(value = 1, message = "参考时长至少 1 分钟")
        @Max(value = 1440, message = "参考时长最长 1440 分钟（24 小时）")
        Integer durationMinutes,

        @Min(value = 1, message = "适用宠物只能是 1（犬）2（猫）3（犬猫）")
        @Max(value = 3, message = "适用宠物只能是 1（犬）2（猫）3（犬猫）")
        Integer applicablePets,

        @Size(max = 512, message = "项目说明最长 512 个字符")
        String description,

        Integer sortOrder) {
}
