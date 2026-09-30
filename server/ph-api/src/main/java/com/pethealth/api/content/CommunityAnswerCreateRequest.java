package com.pethealth.api.content;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 回答，对应 contract/app.yaml 的 {@code CommunityAnswerCreateRequest}。
 *
 * <p>回答也是发文：同样要权益码 {@code community.post}（ADR-0041 第一节），
 * 同样进审核（待审 → 机审命中即被拒 / 运营通过后才出现在提问详情里）。
 *
 * @param content 回答正文（只说经验与建议，不下诊断结论）
 */
public record CommunityAnswerCreateRequest(

        @NotBlank(message = "回答不能为空")
        @Size(max = 2000, message = "回答最长 2000 个字符")
        String content) {
}
