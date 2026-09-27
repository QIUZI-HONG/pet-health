package com.pethealth.api.app;

import jakarta.validation.constraints.NotNull;

/** 切换当前宠物，对应 contract/app.yaml 的 {@code ActivatePetRequest}。 */
public record ActivatePetRequest(@NotNull(message = "pet_id 不能为空") Long petId) {
}
