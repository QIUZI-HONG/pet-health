package com.pethealth.api.app;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 编辑宠物，对应 contract/app.yaml 的 {@code PetUpdateRequest}。
 *
 * <p>只传要改的字段：{@code null} 表示不改。所以这里**没有** {@code @NotBlank} 之类的必填约束，
 * 只有「传了就必须合法」的约束。
 */
public record PetUpdateRequest(

        @Size(max = 64, message = "宠物昵称最长 64 个字符")
        String name,

        @Min(value = 1, message = "物种只能是 1（犬）或 2（猫）")
        @Max(value = 2, message = "物种只能是 1（犬）或 2（猫）")
        Integer species,

        @Size(max = 64, message = "品种最长 64 个字符")
        String breed,

        @Min(value = 0, message = "性别只能是 0/1/2")
        @Max(value = 2, message = "性别只能是 0/1/2")
        Integer gender,

        LocalDate birthday,

        @Pattern(regexp = "^\\d{1,3}(\\.\\d{1,2})?$", message = "体重需为最多两位小数的数字")
        @DecimalMin(value = "0.01", message = "体重需大于 0")
        @DecimalMax(value = "999.99", message = "体重需小于 1000")
        String weight,

        @Size(max = 512, message = "头像地址最长 512 个字符")
        String avatar,

        @JsonProperty("is_sterilized") Boolean isSterilized,

        @JsonProperty("is_chronic") Boolean isChronic,

        @Size(max = 512, message = "慢病描述最长 512 个字符")
        String chronicDesc) {
}
