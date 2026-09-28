package com.pethealth.api.app;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 添加宠物，对应 contract/app.yaml 的 {@code PetCreateRequest}。
 *
 * <p>「慢病描述在 {@code is_chronic=true} 时必填」是跨字段规则，注解表达不了，
 * 由 service 校验——硬塞进注解只会让报错信息变得读不懂。
 *
 * <p>{@code weight} 的形态与范围同理**不在这里写第二遍**：规则只有一份，
 * 在 {@code ph-record} 的 {@code Weight} 值对象里（打卡与建档共用）。注解里再抄一份，
 * 改一处忘一处就是测试报告 D1 的成因（打卡路径当时没有校验，垃圾体重被写进趋势）。
 */
public record PetCreateRequest(

        @NotBlank(message = "宠物昵称不能为空")
        @Size(max = 64, message = "宠物昵称最长 64 个字符")
        String name,

        @NotNull(message = "物种不能为空")
        @Min(value = 1, message = "物种只能是 1（犬）或 2（猫）")
        @Max(value = 2, message = "物种只能是 1（犬）或 2（猫）")
        Integer species,

        @Size(max = 64, message = "品种最长 64 个字符")
        String breed,

        @Min(value = 0, message = "性别只能是 0/1/2")
        @Max(value = 2, message = "性别只能是 0/1/2")
        Integer gender,

        LocalDate birthday,

        String weight,

        @Size(max = 512, message = "头像地址最长 512 个字符")
        String avatar,

        @JsonProperty("is_sterilized") Boolean isSterilized,

        @JsonProperty("is_chronic") Boolean isChronic,

        @Size(max = 512, message = "慢病描述最长 512 个字符")
        String chronicDesc) {
}
