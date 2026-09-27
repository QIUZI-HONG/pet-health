package com.pethealth.api.app;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 提交打卡，对应 contract/app.yaml 的 {@code CheckInSubmitRequest}。
 *
 * <p>{@code date} 是**业务日期**而不是写入时间：传今天=当场录入，传过去 7 天内=补录（ADR-0018）。
 * 分开这两个概念是因为补录必须有独立的业务日期，否则补录的记录会算到今天头上，
 * 连续天数与评分窗口都会错。
 */
public record CheckInSubmitRequest(

        @NotNull(message = "date 不能为空")
        @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "date 格式应为 YYYY-MM-DD")
        String date,

        @NotEmpty(message = "至少提交一项")
        @Size(max = 6, message = "一次最多提交六项")
        @Valid
        List<CheckInItemInput> items) {
}
