package com.pethealth.api.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 审核驳回，对应 contract 的 {@code ReviewRejectRequest}。
 *
 * <p>{@code reason} **必填**：驳回原因会原样出现在服务者后台（用户故事 62 要求「知道卡在哪」），
 * 空原因的驳回等于让服务者去猜。文案是给用户看的，别写内部术语。
 */
public record ReviewRejectRequest(

        @NotBlank(message = "驳回原因不能为空")
        @Size(max = 255, message = "驳回原因最长 255 个字符")
        String reason) {
}
