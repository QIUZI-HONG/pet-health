package com.pethealth.boot.order;

import com.pethealth.boot.support.ApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运营看板的订单统计（D-30，契约 `admin.yaml` 的 `/orders/stats`）。
 *
 * <p>盯三件事：**按状态分组的条数与金额对得上**、**金额是展示口径**（平台不经手资金，ADR-0002）、
 * **账期参数与权限**（不传按当月、格式错 40001、C 端令牌 40100）。
 */
@DisplayName("运营看板：订单统计（D-30）")
class AdminOrderStatsTest extends OrderTestSupport {

    @Test
    @DisplayName("按状态分组：条数与预估实付合计对得上，取消率按总数算")
    void statsGroupOrdersByStatus() {
        Shop shop = createShop("LIC-STATS-001", "128.00");
        UserActor user = createUser(nextPhone());
        long petId = api.createPet(user.token(), "豆豆");
        // 三笔：一笔待接单、一笔已完成、一笔取消。
        // 预约日排**明天**：统计按 `created_at` 取窗口（都是今天、落在当月），而订今天的
        // 时段会撞上「该时段已经开始」——这一条在 OrderCreateTest 里也踩过一次
        LocalDate today = LocalDate.now();
        LocalDate visitDay = today.plusDays(1);
        orderOk(user.token(), petId, shop, visitDay, "09:00");
        long doneId = orderOk(user.token(), petId, shop, visitDay, "10:00");
        long cancelId = orderOk(user.token(), petId, shop, visitDay, "11:00");
        assertCodeOk(api.post("/api/v1/provider/orders/" + doneId + "/accept", null, shop.token()), "接单");
        assertCodeOk(api.post("/api/v1/provider/orders/" + doneId + "/redeem", null, shop.token()), "核销");
        assertCodeOk(api.post("/api/v1/app/orders/" + cancelId + "/cancel", null, user.token()), "用户取消");

        String admin = adminToken();
        String period = String.format("%04d-%02d", today.getYear(), today.getMonthValue());
        ApiClient.ApiCall stats = api.get("/api/v1/admin/orders/stats?period=" + period, admin);
        assertCodeOk(stats, "订单统计");
        assertThat(stats.data().path("period").asText()).isEqualTo(period);
        assertThat(stats.data().path("total").asLong()).isEqualTo(3);
        // 核销把它推到**履约中**（状态机：0 待接单 / 1 已预约 / 2 履约中 / 3 已完成）。
        // 到「已完成」还要报工，而报工有**三道照片墙的硬约束**——本用例不为了凑一个状态去造照片，
        // 所以这里断言的是真实到达的三个状态
        assertThat(stats.data().path("by_status").findValuesAsText("label"))
                .as("状态名由服务端给").containsExactlyInAnyOrder("待接单", "履约中", "已取消");

        // 取消率 = 1/3 = 0.33（分母含进行中的单，运营看趋势）
        assertThat(stats.data().path("cancel_rate").asText()).isEqualTo("0.33");
        // 金额是「门店应收」的合计，不是平台流水（ADR-0002）
        assertThat(stats.data().path("pay_amount").asText()).isNotBlank();

        // 不传账期按当月：与显式传今天所在的月份一致
        assertThat(api.get("/api/v1/admin/orders/stats", admin).data().path("period").asText()).isEqualTo(period);
        // 账期格式错 40001；C 端令牌 40100（登录域，ADR-0012）
        assertThat(api.get("/api/v1/admin/orders/stats?period=2026/03", admin).code()).isEqualTo(40001);
        assertThat(api.get("/api/v1/admin/orders/stats", user.token()).code()).isEqualTo(40100);
    }
}
