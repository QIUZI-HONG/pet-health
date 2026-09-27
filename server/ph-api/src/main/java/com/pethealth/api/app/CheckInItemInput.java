package com.pethealth.api.app;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 打卡的一项，对应 contract/app.yaml 的 {@code CheckInItemInput}。
 *
 * <p>{@code abnormal} 是打卡里最有信息量的字段——评分按它扣分（ADR-0018），
 * 所以前端「异常」这个动作要被认真对待，不能默认勾上。
 */
public record CheckInItemInput(

        @NotNull(message = "category 不能为空")
        @Min(value = 1, message = "分项只能是 1–6")
        @Max(value = 6, message = "分项只能是 1–6")
        Integer category,

        @JsonProperty("abnormal") Boolean abnormal,

        @Size(max = 32, message = "取值最长 32 个字符")
        String value,

        @Size(max = 200, message = "备注最长 200 个字符")
        String note) {
}
