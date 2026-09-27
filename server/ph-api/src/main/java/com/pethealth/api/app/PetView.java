package com.pethealth.api.app;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 宠物档案，对应 contract/app.yaml 的 {@code Pet}。
 *
 * <p>{@code weight} 用 {@code String} 而不是数字：小数在 JS 里会丢精度（ADR-0011），
 * 序列化时由全局 Jackson 配置把 BigDecimal 转成字符串。
 *
 * <p>{@code restorableUntil} 只在回收站（{@code deleted=true}）里有值。
 *
 * <p>带 {@code is_} 前缀的两个字段显式写 {@link JsonProperty}：Java 的 boolean 取值器会让
 * Jackson 把 {@code isSterilized} 认成 {@code sterilized}，与契约对不上。其余字段交给全局 snake_case。
 */
public record PetView(
        Long id,
        String name,
        Integer species,
        String breed,
        Integer gender,
        LocalDate birthday,
        String weight,
        String avatar,
        @JsonProperty("is_sterilized") boolean isSterilized,
        @JsonProperty("is_chronic") boolean isChronic,
        String chronicDesc,
        LocalDateTime restorableUntil,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
