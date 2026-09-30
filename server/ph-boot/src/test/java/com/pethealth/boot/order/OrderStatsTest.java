package com.pethealth.boot.order;

import com.pethealth.boot.support.ApiClient;
import com.pethealth.order.api.OrderStatsApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 订单侧只读统计（{@link OrderStatsApi}，给考核 F022 用）。
 *
 * <p>钉住两件事：**有数据时口径对**，以及**无数据时「计数回 0、均值回 null」**——
 * 0 与 null 在考核里是两种事实，混掉会让「这段时间一单没有」与「有单但还没有可算的时长」
 * 得到同一个分数（ADR-0049 的考核口径）。
 */
@DisplayName("订单侧只读统计（考核 F022 的口径）")
class OrderStatsTest extends OrderTestSupport {

    @Autowired
    private OrderStatsApi stats;

    @Test
    @DisplayName("有数据：单量按状态分组、服务者单方取消单独计、两个均值取自服务端时刻")
    void statsWithData() {
        Shop shop = createShop("LIC-STATS-001", "128.00");
        UserActor user = createUser(nextPhone());
        long petId = api.createPet(user.token(), "豆豆");
        LocalDate date = tomorrow();

        // ① 一单走到「已完成」：接单 → 核销 → 三道照片墙 → 报工
        long completed = orderOk(user.token(), petId, shop, date, "09:00");
        assertCodeOk(api.post("/api/v1/provider/orders/" + completed + "/accept", null, shop.token()), "接单");
        assertCodeOk(api.post("/api/v1/provider/orders/" + completed + "/redeem", null, shop.token()), "核销");
        fillPhotoWall(shop.token(), user.token(), completed, petId);
        assertCodeOk(api.post("/api/v1/provider/orders/" + completed + "/report",
                Map.of("remark", "完成"), shop.token()), "报工");

        // ② 一单被门店单方取消
        long cancelled = orderOk(user.token(), petId, shop, date, "10:00");
        assertCodeOk(api.post("/api/v1/provider/orders/" + cancelled + "/cancel",
                Map.of("reason", "设备检修"), shop.token()), "门店取消");

        // ③ 一单还在待接单
        orderOk(user.token(), petId, shop, date, "11:00");

        // 把两个时间戳人工拉开，等价于「接单用了 10 分钟、服务用了 40 分钟」（服务端时刻）
        jdbc.update("UPDATE `order` SET `accepted_at` = DATE_ADD(`created_at`, INTERVAL 10 MINUTE) "
                + "WHERE `id` = ?", completed);
        jdbc.update("UPDATE `order` SET `reported_at` = DATE_ADD(`redeemed_at`, INTERVAL 40 MINUTE) "
                + "WHERE `id` = ?", completed);

        LocalDate from = date.minusDays(1);
        LocalDate to = date.plusDays(1);
        assertThat(stats.countByProvider(shop.providerId(), from, to, null)).isEqualTo(3);
        assertThat(stats.countByProvider(shop.providerId(), from, to, 3)).as("已完成 1 单").isEqualTo(1);
        assertThat(stats.countByProvider(shop.providerId(), from, to, 0)).as("待接单 1 单").isEqualTo(1);
        assertThat(stats.countCancelledByProvider(shop.providerId(), from, to))
                .as("服务者单方取消只算 cancelled_by=2 的那一单").isEqualTo(1);

        assertThat(stats.averageResponseMinutes(shop.providerId(), from, to)).isEqualTo(10L);
        assertThat(stats.averageServiceMinutes(shop.providerId(), from, to)).isEqualTo(40L);

        // 别的门店查不到这些单（统计也按归属隔离）
        Shop other = createShop("LIC-STATS-002", "128.00");
        assertThat(stats.countByProvider(other.providerId(), from, to, null)).isZero();
        assertThat(stats.averageServiceMinutes(other.providerId(), from, to)).isNull();
        // 区间之外查不到（口径按预约日期）
        assertThat(stats.countByProvider(shop.providerId(), date.plusDays(5), date.plusDays(6), null)).isZero();
    }

    @Test
    @DisplayName("无数据：计数回 0、均值回 null（不是 0）")
    void statsWithoutData() {
        Shop shop = createShop("LIC-STATS-003", "128.00");
        LocalDate from = LocalDate.now().plusDays(10);
        LocalDate to = LocalDate.now().plusDays(20);

        assertThat(stats.countByProvider(shop.providerId(), from, to, null)).isZero();
        assertThat(stats.countCancelledByProvider(shop.providerId(), from, to)).isZero();
        assertThat(stats.averageResponseMinutes(shop.providerId(), from, to))
                .as("没有可算的单 → null，考核据此区分「没有数据」与「时长为 0」").isNull();
        assertThat(stats.averageServiceMinutes(shop.providerId(), from, to)).isNull();

        // 有单但还没接单：单量进统计，响应时长仍然是 null（不是把未接单当成 0 分钟）
        UserActor user = createUser(nextPhone());
        long petId = api.createPet(user.token(), "豆豆");
        orderOk(user.token(), petId, shop, from.plusDays(1), "09:00");
        assertThat(stats.countByProvider(shop.providerId(), from, to, null)).isEqualTo(1);
        assertThat(stats.averageResponseMinutes(shop.providerId(), from, to)).isNull();
    }
}
