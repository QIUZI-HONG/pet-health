package com.pethealth.api.content;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 提问，对应 contract/app.yaml 的 {@code CommunityQuestionCreateRequest}。
 *
 * <p>发文要权益码 {@code community.post}（ADR-0041 第一节）：判定走 ph-privilege 的
 * {@code RightsApi}，社区不自己查表拼规则（ADR-0038 第三节）。没有该权益 → 40300，
 * 与「未登录」的 40100 分开。
 *
 * @param title       问题标题
 * @param content     问题正文（可补充症状、年龄、已做过的处理）
 * @param diseaseTag  慢病标签（按它聚合「同病」的问答）
 */
public record CommunityQuestionCreateRequest(

        @NotBlank(message = "标题不能为空")
        @Size(max = 64, message = "标题最长 64 个字符")
        String title,

        @NotBlank(message = "正文不能为空")
        @Size(max = 2000, message = "正文最长 2000 个字符")
        String content,

        @Size(max = 32, message = "慢病标签最长 32 个字符")
        String diseaseTag) {
}
