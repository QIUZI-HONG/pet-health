package com.pethealth.api.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 处置一条转人工工单，对应 contract/admin.yaml 的 {@code HumanConsultStatusRequest}。
 *
 * <p>只开放 1（已回复）与 2（已关闭）：0 是初始态，**不能设回**——把已处理的单子「退回待处理」
 * 会让「谁在处理」这件事失去意义，需要重开就新建一条。
 *
 * <p>`replyNote` 在 `status=1` 时**必填**（服务端判，不在注解上：条件必填用 Bean Validation
 * 表达要自定义校验器，而这里只有一条规则，写在 service 里更直白）：它会被原样作为站内消息发给用户。
 */
public record HumanConsultStatusRequest(

        @NotNull(message = "状态不能为空")
        @Min(value = 1, message = "状态只能是 1（已回复）或 2（已关闭）")
        @Max(value = 2, message = "状态只能是 1（已回复）或 2（已关闭）")
        Integer status,

        @Size(max = 500, message = "回复内容最长 500 个字符")
        String replyNote) {
}
