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
 * <p><b>{@code fileId} 必填</b>（ADR-0053）：材料的图是审核的依据，没有图审核员无从判断。
 * 它是 {@code biz_type=qualification} 上传所得（ADR-0020，字节不走业务接口）——
 * <b>请求里给的是 id，不是地址</b>：地址是签名 URL、会过期，存进库就是死链。
 */
public record ProviderQualificationRequest(

        @NotNull(message = "资质类型不能为空")
        Integer type,

        @Size(max = 128, message = "材料名称最长 128 个字符")
        String name,

        @Pattern(regexp = "^[\\x20-\\x7E]{0,64}$", message = "证件号只能用可见字符，最长 64 位")
        String certNo,

        @NotNull(message = "请先上传材料图片")
        Long fileId,

        LocalDate validFrom,

        LocalDate validUntil) {
}
