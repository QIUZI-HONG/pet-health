package com.pethealth.boot.order;

import com.pethealth.boot.support.ApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 号源（切片 #109；决策见 ADR-0038 第一节、CONTEXT.md 的「号源」条目）。
 *
 * <p>盯三件事：**时段网格来自营业时间**、**约满的时段照样返回**（前端要显示「已满」，
 * 不能让它静默消失）、以及**并发抢同一时段只有一个成功**（ADR-0038 与交付文档 2.4 的验收项）。
 */
@DisplayName("号源：时段网格 / 约满标记 / 并发抢同一时段（切片 #109 / ADR-0038）")
class AppointmentSlotTest extends OrderTestSupport {

    @Test
    @DisplayName("号源按时段网格返回：容量、已占用、剩余与「已满」标记都对得上")
    void slotsFollowBusinessHours() {
        Shop shop = createShop("LIC-SLOT-001", "128.00");
        UserActor user = createUser(nextPhone());
        long petId = api.createPet(user.token(), "豆豆");
        LocalDate date = tomorrow();

        ApiClient.ApiCall slots = api.get("/api/v1/app/providers/" + shop.providerId()
                + "/appointment-slots?service_id=" + shop.serviceId() + "&date=" + date, user.token());
        assertCodeOk(slots, "查号源");
        // 09:00–18:00 按 **30 分钟一格**切（ADR-0049 §六）：09:00 到 17:30 共 18 格
        assertThat(slots.data()).hasSize(18);
        assertThat(slots.data().get(0).path("start_time").asText()).isEqualTo("09:00");
        assertThat(slots.data().get(0).path("end_time").asText()).isEqualTo("09:30");
        assertThat(slots.data().get(1).path("start_time").asText()).isEqualTo("09:30");
        assertThat(slots.data().get(17).path("start_time").asText()).isEqualTo("17:30");
        assertThat(slots.data().get(0).path("capacity").asInt()).isEqualTo(1);
        assertThat(slots.data().get(0).path("booked_count").asInt()).isZero();
        assertThat(slots.data().get(0).path("available_count").asInt()).isEqualTo(1);
        assertThat(slots.data().get(0).path("full").asBoolean()).isFalse();

        // 约满之后：**这个时段仍然在列表里**，只是 full=true（约满就消失会让用户以为门店不营业）
        long orderId = orderOk(user.token(), petId, shop, date, "09:00");
        ApiClient.ApiCall afterBooked = api.get("/api/v1/app/providers/" + shop.providerId()
                + "/appointment-slots?service_id=" + shop.serviceId() + "&date=" + date, user.token());
        assertThat(afterBooked.data().get(0).path("booked_count").asInt()).isEqualTo(1);
        assertThat(afterBooked.data().get(0).path("available_count").asInt()).isZero();
        assertThat(afterBooked.data().get(0).path("full").asBoolean()).isTrue();
        assertThat(afterBooked.data().get(1).path("full").asBoolean()).isFalse();

        // 号源落到了行上（懒建：第一次预约才建这一行）
        assertThat(slotBooked(shop.providerId(), shop.serviceId(), date, "09:00")).isEqualTo(1);
        assertThat(orderId).isPositive();
    }

    @Test
    @DisplayName("不营业的那天没有时段；日期已过去 → 40001；别的门店的服务项 → 40400")
    void slotBoundaries() {
        Shop shop = createShop("LIC-SLOT-002", "128.00");
        UserActor user = createUser(nextPhone());

        // 把营业时间改成只有「明天的星期几」，于是后天不营业
        LocalDate date = tomorrow();
        int weekday = date.getDayOfWeek().getValue();
        assertCodeOk(api.put("/api/v1/provider/profile/business-hours",
                Map.of("hours", List.of(Map.of("day_of_week", weekday,
                        "open_time", OPEN_TIME, "close_time", CLOSE_TIME))), shop.token()), "改营业时间");

        assertThat(api.get("/api/v1/app/providers/" + shop.providerId() + "/appointment-slots?service_id="
                + shop.serviceId() + "&date=" + date, user.token()).data()).hasSize(18);
        assertThat(api.get("/api/v1/app/providers/" + shop.providerId() + "/appointment-slots?service_id="
                        + shop.serviceId() + "&date=" + date.plusDays(2), user.token()).data())
                .as("不营业的那天没有任何时段")
                .isEmpty();

        ApiClient.ApiCall past = api.get("/api/v1/app/providers/" + shop.providerId()
                + "/appointment-slots?service_id=" + shop.serviceId() + "&date=" + date.minusDays(2),
                user.token());
        assertThat(past.code()).as("日期已过去").isEqualTo(40001);

        Shop other = createShop("LIC-SLOT-003", "88.00");
        ApiClient.ApiCall foreign = api.get("/api/v1/app/providers/" + other.providerId()
                + "/appointment-slots?service_id=" + shop.serviceId() + "&date=" + date, user.token());
        assertThat(foreign.code()).as("服务项不属于这家门店，按不存在处理").isEqualTo(40400);
    }

    @Test
    @DisplayName("并发抢同一时段：只有一个成功，其余 40900（容量默认 1）")
    void concurrentBookingKeepsOneWinner() throws Exception {
        Shop shop = createShop("LIC-SLOT-004", "128.00");
        UserActor user = createUser(nextPhone());
        long petId = api.createPet(user.token(), "豆豆");
        LocalDate date = tomorrow();

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<String> unexpected = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await(10, TimeUnit.SECONDS);
                        ApiClient.ApiCall call = placeOrder(user.token(), petId, shop, date, "10:00");
                        if (call.code() == 0) {
                            success.incrementAndGet();
                        } else if (call.code() == 40900) {
                            conflict.incrementAndGet();
                        } else {
                            synchronized (unexpected) {
                                unexpected.add(call.body().toString());
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(unexpected).as("失败者只该拿到 40900「该时段已被预约」").isEmpty();
        assertThat(success.get()).as("容量 1 的时段只能有一个订单占上").isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(threads - 1);
        assertThat(slotBooked(shop.providerId(), shop.serviceId(), date, "10:00")).isEqualTo(1);
        Integer orders = jdbc.queryForObject("SELECT COUNT(*) FROM `order` WHERE `provider_id` = ? "
                + "AND `appointment_date` = ? AND `start_time` = '10:00'", Integer.class,
                shop.providerId(), date);
        assertThat(orders).as("库里也只能有一条订单占着这个时段").isEqualTo(1);
    }

    @Test
    @DisplayName("打烊落在午夜边界（23:30）：网格不绕回原点，47 格、末格 23:00–23:30")
    void gridStopsAtMidnightBoundary() {
        Shop shop = createShop("LIC-SLOT-BOUNDARY", "128.00");
        UserActor user = createUser(nextPhone());
        LocalDate date = tomorrow();

        // 00:00–23:30：最后一格是 23:00–23:30。切网格的循环曾经拿 `LocalTime` 当循环变量，
        // 而 `23:30.plusMinutes(30)` 会**绕回 00:00**——「还有下一格吗」这个判定因此永远成立，
        // 列表一路加到堆爆。触发条件不是脏数据，而是**正常的营业时间**（打烊 23:31–23:59，或正好
        // 23:30），所以这个用例盯的是「网格有界且末格正确」。
        assertCodeOk(api.put("/api/v1/provider/profile/business-hours",
                Map.of("hours", List.of(Map.of("day_of_week", date.getDayOfWeek().getValue(),
                        "open_time", "00:00", "close_time", "23:30"))), shop.token()), "改营业时间");

        ApiClient.ApiCall slots = api.get("/api/v1/app/providers/" + shop.providerId()
                + "/appointment-slots?service_id=" + shop.serviceId() + "&date=" + date, user.token());
        assertCodeOk(slots, "查号源");
        assertThat(slots.data()).hasSize(47);   // 00:00 → 23:00，每 30 分钟一格
        assertThat(slots.data().get(46).path("start_time").asText()).isEqualTo("23:00");
        assertThat(slots.data().get(46).path("end_time").asText()).isEqualTo("23:30");
    }
}
