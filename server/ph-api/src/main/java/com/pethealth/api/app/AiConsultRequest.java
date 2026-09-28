package com.pethealth.api.app;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 一次 AI 健康咨询，对应 contract/app.yaml 的 {@code AiConsultRequest}。
 *
 * <p>{@code question} **必填**，不是可选项：纯图片分诊不可用（61 号调研实测皮肤病零样本只有 33%），
 * 补上症状文本才到 72–94%。这条约束在接口层就卡住，别指望提示词。
 *
 * @param question 用户描述的症状，2–500 字
 * @param fileIds  参与判断的图片（切片 #95 上传后的 file_id）。最多 4 张——模型看太多图会明显变慢
 */
public record AiConsultRequest(
        @NotBlank(message = "请描述一下症状")
        @Size(min = 2, max = 500, message = "症状描述请控制在 2–500 字")
        String question,

        @Size(max = 4, message = "一次最多带 4 张图片")
        List<Long> fileIds) {
}
