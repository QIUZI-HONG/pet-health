package com.pethealth.api.app;

import jakarta.validation.constraints.NotNull;

/** 修改某类提醒的开关，对应 contract/app.yaml 的 {@code ReminderSettingRequest}。 */
public record ReminderSettingRequest(
        @NotNull(message = "type 不能为空") Integer type,
        @NotNull(message = "enabled 不能为空") Boolean enabled) {
}
