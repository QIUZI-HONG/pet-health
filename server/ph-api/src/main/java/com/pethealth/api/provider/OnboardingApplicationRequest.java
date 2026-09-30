package com.pethealth.api.provider;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 提交 / 修改入驻申请，对应 contract 的 {@code OnboardingApplicationRequest}。
 *
 * <p>同一份请求体用于「首次提交」与「驳回后重提」：重提是**整体覆盖**（门店信息与材料一起换掉），
 * 而不是逐个字段改——审核员看到的应该是一份完整的新材料，而不是「上一版 + 三个补丁」。
 *
 * <p>{@code contactPhone} 明文进、密文存、脱敏出（ADR-0013）。
 * {@code lng} / {@code lat} 是**字符串**（小数在 JS 里会丢精度，ADR-0011），两者要么都给要么都不给。
 *
 * <p>资质材料至少一条：没有材料的入驻申请必然被驳回，与其让它进审核队列再退回，
 * 不如在入口就拦住（交付文档 F012 要求按类型提交不同材料，清单待澄清，见 ADR-0035）。
 */
public record OnboardingApplicationRequest(

        @NotBlank(message = "服务者名称不能为空")
        @Size(max = 256, message = "服务者名称最长 256 个字符")
        String name,

        @NotNull(message = "服务者类型不能为空")
        @Min(value = 1, message = "服务者类型只能是 1–6")
        @Max(value = 6, message = "服务者类型只能是 1–6")
        Integer type,

        @Min(value = 1, message = "经营主体分类只能是 1（直接同业）2（直接异业）3（间接异业）")
        @Max(value = 3, message = "经营主体分类只能是 1（直接同业）2（直接异业）3（间接异业）")
        Integer category,

        @Size(max = 512, message = "Logo 地址最长 512 个字符")
        String logo,

        @Size(max = 1024, message = "门店简介最长 1024 个字符")
        String intro,

        @NotBlank(message = "门店地址不能为空")
        @Size(max = 512, message = "门店地址最长 512 个字符")
        String address,

        String lng,

        String lat,

        @NotBlank(message = "联系电话不能为空")
        @Pattern(regexp = "^(1[3-9]\\d{9}|0\\d{2,3}-?\\d{7,8})$", message = "联系电话格式不正确")
        String contactPhone,

        @NotBlank(message = "申请人姓名不能为空")
        @Size(max = 64, message = "申请人姓名最长 64 个字符")
        String applicantName,

        @NotEmpty(message = "至少提交一份资质材料")
        @Size(max = 20, message = "一次最多提交 20 份材料")
        @Valid
        List<ProviderQualificationRequest> qualifications) {
}
