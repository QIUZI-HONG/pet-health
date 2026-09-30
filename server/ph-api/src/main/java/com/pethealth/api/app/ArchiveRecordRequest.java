package com.pethealth.api.app;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 录入 / 修改一条分项记录，对应 contract/app.yaml 的 {@code ArchiveRecordRequest}（切片 #102）。
 *
 * <p><b>可写的分项只有四个</b>：{@code metrics}（其他核心指标，落 category=10）、
 * {@code documents}（证件，落 11）、{@code elderly}（老年专项，落 12）、{@code medical}（就医，落 8）。
 *
 * <p>体重与排泄**不在这里**：它们是打卡六项，有「一天一条、重复即更新、可撤销、进评分完整度」
 * 的语义，走 {@code /check-ins}；防疫同理走 {@code /epidemic-records}。
 * 字段名与形状刻意与打卡一致（都走 {@code AppTime.parseDate} 与同一套 7 天窗口），
 * 免得两条录入路径对「什么日期算合法」给出两套答案。
 */
public record ArchiveRecordRequest(

        @NotBlank(message = "分项不能为空")
        @Pattern(regexp = "^(metrics|documents|elderly|medical)$",
                message = "分项只能是 metrics / documents / elderly / medical")
        String section,

        @NotBlank(message = "日期不能为空")
        @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "date 格式应为 YYYY-MM-DD")
        String date,

        @NotBlank(message = "标题不能为空")
        @Size(max = 64, message = "标题最长 64 个字符")
        String title,

        @Size(max = 128, message = "取值最长 128 个字符")
        String value,

        @Size(max = 16, message = "单位最长 16 个字符")
        String unit,

        @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "due_on 格式应为 YYYY-MM-DD")
        String dueOn,

        @Size(max = 500, message = "备注最长 500 个字符")
        String note,

        Boolean abnormal) {
}
