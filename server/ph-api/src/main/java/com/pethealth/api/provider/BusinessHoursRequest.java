package com.pethealth.api.provider;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 维护营业时间，对应 contract 的 {@code BusinessHoursRequest}。
 *
 * <p>**整体替换**（不是按天改）：营业时间是「一周的样子」这一个整体，
 * 逐天改会让「今天到底开不开」取决于调用顺序。空数组表示「整周休息」，不是「不改」。
 */
public record BusinessHoursRequest(

        @Size(max = 7, message = "一周最多 7 条营业时段")
        @Valid
        List<BusinessHour> hours) {
}
