package com.pethealth.record.event;

import java.time.LocalDate;
import java.util.List;

/**
 * 打卡已记录（领域事件，ADR-0006：模块间只能走接口或领域事件）。
 *
 * <p>发事件而不是直接调提醒模块：档案模块不该知道「提醒」这件事存在。
 * 谁关心谁自己去听——目前只有提醒模块关心「有没有异常」。
 *
 * @param abnormalCategories 本次被标注为异常的分项（空表示没标异常）
 */
public record CheckInRecordedEvent(
        long userId,
        long petId,
        String petName,
        LocalDate recordDate,
        List<Integer> abnormalCategories) {

    public boolean hasAbnormal() {
        return abnormalCategories != null && !abnormalCategories.isEmpty();
    }
}
