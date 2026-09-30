package com.pethealth.boot.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 预约提醒（F026）——在预约开始前若干小时给用户发一条站内消息。
 *
 * <p>这个批算没有 HTTP 入口，所以按本仓其它批算的同一口径**直接调 {@code remind()}**
 * （ADR-0014 的「只测 HTTP 缝」在这一处无法成立，理由与券过期、资质到期、月度考核相同）。
 * 断言仍然只看外部可观察状态：{@code message} 表里的那一行。
 *
 * <p><b>窗口在本类里放宽到 48 小时</b>（生产默认 2 小时）：下单的槽位来自「明天 09:00」
 * 这类固定时段（{@code OrderTestSupport.tomorrow()}），2 小时窗口在一天里的大部分时刻都覆盖不到它。
 * 放宽窗口只影响这一条用例，而它要验的是「窗口内的单会被提醒、同一单只提醒一次」——
 * 与窗口取值无关。
 */
@TestPropertySource(properties = "app.order.appointment-reminder-hours=48")
class AppointmentReminderTest extends OrderTestSupport {

    @Autowired
    private com.pethealth.order.service.AppointmentReminderJob reminderJob;

    @Test
    @DisplayName("窗口内的预约发一条提醒；再跑一轮不重复发（去重键按订单）")
    void remindsOncePerOrder() {
        Shop shop = createShop("LIC-REMIND-001", "128.00");
        UserActor user = createUser("14000009001");
        long petId = api.createPet(user.token(), "豆豆");
        long orderId = orderOk(user.token(), petId, shop, tomorrow(), OPEN_TIME);

        assertThat(reminderJob.remind()).as("明天的单在 48 小时窗口里").isEqualTo(1);
        assertThat(messageCount(user.userId(), "order-appointment-" + orderId)).isEqualTo(1L);

        // 每小时跑一轮：同一单在窗口里会被扫到多次，但只该有一条消息
        assertThat(reminderJob.remind()).isEqualTo(1);
        assertThat(messageCount(user.userId(), "order-appointment-" + orderId)).isEqualTo(1L);
    }

    @Test
    @DisplayName("已取消的单不再提醒（提醒一条已经作废的预约只会让人白跑）")
    void doesNotRemindCancelledOrders() {
        Shop shop = createShop("LIC-REMIND-002", "128.00");
        UserActor user = createUser("14000009002");
        long petId = api.createPet(user.token(), "豆豆");
        long orderId = orderOk(user.token(), petId, shop, tomorrow(), OPEN_TIME);
        assertCodeOk(api.post("/api/v1/app/orders/" + orderId + "/cancel", null, user.token()), "用户取消");

        reminderJob.remind();
        assertThat(messageCount(user.userId(), "order-appointment-" + orderId)).isZero();
    }

    /** 数一条业务通知（去重键是它在这张表里的唯一标识）。 */
    private long messageCount(long userId, String dedupKey) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM `message` WHERE user_id = ? AND dedup_key = ?",
                Long.class, userId, dedupKey);
    }
}
