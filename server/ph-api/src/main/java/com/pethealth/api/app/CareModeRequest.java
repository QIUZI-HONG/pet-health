package com.pethealth.api.app;

import jakarta.validation.constraints.NotNull;

/**
 * 打开 / 关闭专项照护模式，对应 contract/app.yaml 的 {@code CareModeRequest}（切片 #116）。
 *
 * <p><b>它只用于「用户手动关闭」</b>：{@code enabled=false} 落一枚「用户关掉了」的位；
 * {@code enabled=true} 是**清除这枚位**、把判定交还给派生规则——如果宠物确实不满年龄且没有慢病，
 * 打开后仍是未开启。**不能把一只 2 岁的健康宠物手动变成照护模式**（ADR-0032 决定一）。
 */
public record CareModeRequest(@NotNull(message = "enabled 不能为空") Boolean enabled) {
}
