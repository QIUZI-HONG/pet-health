package com.pethealth.api.provider;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 一天营业时段，对应 contract 的 {@code BusinessHour}。
 *
 * <p>只列**营业的那几天**：数组里没有的星期几就是休息。用「不出现 = 不营业」而不是
 * {@code closed: true} 这样的标记，是因为后者会出现「既有时段又标了休息」这种自相矛盾的行，
 * 而前者在结构上不可能矛盾。
 *
 * <p>时间用 {@code HH:mm} 字符串（项目约定，docs/conventions.md）：跨天的营业时段（22:00–02:00）
 * 本期不支持——预约时段校验在下单链路（#77），到那时再决定要不要跨天，现在不编。
 */
public record BusinessHour(

        @NotNull(message = "星期不能为空")
        @Min(value = 1, message = "星期只能是 1（周一）–7（周日）")
        @Max(value = 7, message = "星期只能是 1（周一）–7（周日）")
        Integer dayOfWeek,

        @NotNull(message = "开始时间不能为空")
        @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "开始时间格式应为 HH:mm")
        String openTime,

        @NotNull(message = "结束时间不能为空")
        @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "结束时间格式应为 HH:mm")
        String closeTime) {
}
