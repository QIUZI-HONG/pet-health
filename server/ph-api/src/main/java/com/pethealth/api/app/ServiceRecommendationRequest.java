package com.pethealth.api.app;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * F011「AI 帮我找服务」的请求，对应 contract/app.yaml 的 {@code ServiceRecommendationRequest}。
 *
 * <p>{@code text} 是**用户自己的话**（「我家猫今天拉稀两次」），不需要专业术语：口语别名由
 * 知识侧的受控词典归一（「拉稀」→ 腹泻）。2–500 字与 AI 咨询的文本口径一致——
 * 太短的描述认不出症状（用户会以为是功能坏了），太长的是把整段病历粘进来了。
 */
public record ServiceRecommendationRequest(

        @NotBlank(message = "请描述一下症状")
        @Size(min = 2, max = 500, message = "描述请控制在 2–500 字之间")
        String text) {
}
