package com.pethealth.reminder.service;

import com.pethealth.record.event.CheckInRecordedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 打卡事件 → 异常提醒（ADR-0019：异常类即时生成）。
 *
 * <p>用 {@link TransactionalEventListener} 的 AFTER_COMMIT：打卡事务提交成功才生成提醒。
 * 否则打卡回滚了、提醒却留在消息中心里——用户看到一条指向不存在记录的提醒。
 */
@Component
public class CheckInRecordedListener {

    private final ReminderGenerator reminderGenerator;

    public CheckInRecordedListener(ReminderGenerator reminderGenerator) {
        this.reminderGenerator = reminderGenerator;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCheckInRecorded(CheckInRecordedEvent event) {
        if (event.hasAbnormal()) {
            reminderGenerator.onAbnormalCheckIn(
                    event.userId(), event.petId(), event.petName(), event.recordDate());
        }
    }
}
