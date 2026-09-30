package com.pethealth.api.provider;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 维护门店信息，对应 contract 的 {@code ProviderProfileRequest}。
 *
 * <p>只有「审核通过后」可改（AC：审核通过后可维护门店信息与营业时间），
 * 且**改了不需要重新审核**——交付文档没要求门店信息变更走审核，本切片不自行加一道
 * （理由与被否决的选项见 ADR-0035）。
 *
 * <p>营业时间不在这里：它有独立接口且是整体替换语义（{@link BusinessHoursRequest}），
 * 混在这份请求体里会让「只改营业时间」也带上门店字段的覆盖风险。
 */
public record ProviderProfileRequest(

        @NotBlank(message = "服务者名称不能为空")
        @Size(max = 256, message = "服务者名称最长 256 个字符")
        String name,

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
        String phone) {
}
