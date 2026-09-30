package com.pethealth.api.provider;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 资质材料，对应 contract 的 {@code ProviderQualificationRequest}。
 *
 * <p>{@code certNo} 是**明文进、脱敏出**：服务端按 ADR-0013 加密存密文 + HMAC 查找列，
 * 响应里一律给脱敏值（与手机号同一处理）。所以这个字段只出现在请求体里，
 * 响应体用的是 {@link ProviderQualificationView}，两者不是同一个类。
 *
 * <p>{@code fileUrl} 指向 ph-file 的上传结果（ADR-0020，字节不走业务接口）；
 * 本期不校验「URL 必须存在」，但**审核通过前必须有人看过材料**是流程要求（见 ADR-0035）。
 */
public record ProviderQualificationRequest(

        @NotNull(message = "资质类型不能为空")
        Integer type,

        @Size(max = 128, message = "材料名称最长 128 个字符")
        String name,

        @Pattern(regexp = "^[\\x20-\\x7E]{0,64}$", message = "证件号只能用可见字符，最长 64 位")
        String certNo,

        @Size(max = 512, message = "材料图片地址最长 512 个字符")
        String fileUrl,

        LocalDate validFrom,

        LocalDate validUntil) {
}
