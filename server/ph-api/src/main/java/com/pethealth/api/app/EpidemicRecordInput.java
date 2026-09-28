package com.pethealth.api.app;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 录入一条疫苗 / 驱虫记录，对应 contract/app.yaml 的 {@code EpidemicRecordInput}。
 *
 * <p>{@code nextDueOn} 是疫苗/驱虫提醒的唯一依据：**不填就没有提醒**——宁可少提醒，
 * 也不要替用户猜接种周期（不同疫苗周期不同，猜错比不提醒更糟）。
 */
public record EpidemicRecordInput(

        @NotNull(message = "kind 不能为空")
        @Min(value = 1, message = "kind 只能是 1（疫苗）或 2（驱虫）")
        @Max(value = 2, message = "kind 只能是 1（疫苗）或 2（驱虫）")
        Integer kind,

        @NotBlank(message = "名称不能为空")
        @Size(max = 64, message = "名称最长 64 个字符")
        String name,

        @NotBlank(message = "接种日期不能为空")
        @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "given_on 格式应为 YYYY-MM-DD")
        String givenOn,

        @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "next_due_on 格式应为 YYYY-MM-DD")
        String nextDueOn) {
}
