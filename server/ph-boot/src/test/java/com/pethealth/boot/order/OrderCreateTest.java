package com.pethealth.boot.order;

import com.pethealth.boot.support.ApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 下单（切片 #77；决策见 ADR-0038 第一节、ADR-0036、ADR-0034 第五节）。
 *
 * <p>盯四类被拒绝的规则与一条「钱的口径」：
 *
 * <ul>
 *   <li>时段满了 → 40900；服务项不可选 / 宠物不属于我 → 40400；时段与日期不对 → 40001；
 *       券不可用 → 80001 / 80002；定价被运营收窄到区间外 → 90001；
 *   <li>订单上只有**预估实付**这一个金额口径，没有任何收款 / 结算字段（ADR-0036）。
 * </ul>
 */
@DisplayName("下单：占号源 / 锁券 / 区间复校（切片 #77 / ADR-0038 / ADR-0034 / ADR-0036）")
class OrderCreateTest extends OrderTestSupport {

    @Test
    @DisplayName("下单成功：占号源、锁券、快照服务项与宠物，金额只有「预估实付」一个口径")
    void createOrderOccupiesSlotAndLocksCoupon() {
        Shop shop = createShop("LIC-ORDER-001", "128.00");
        UserActor user = createUser(nextPhone());
        long petId = api.createPet(user.token(), "豆豆");
        long templateId = createSubsidyTemplate("CP-201", "30.00", "100.00");
        long couponId = issueCoupon(user.userId(), templateId);
        LocalDate date = tomorrow();

        ApiClient.ApiCall created = placeOrder(user.token(), petId, shop, date, "09:00", couponId);
        assertCodeOk(created, "下单");
        long orderId = created.data().path("id").asLong();

        assertThat(created.data().path("status").asInt()).isZero();
        assertThat(created.data().path("order_no").asText()).startsWith("PH");
        assertThat(created.data().path("service_name").asText()).isEqualTo("基础洗护（小型犬）");
        assertThat(created.data().path("pet_name").asText()).isEqualTo("豆豆");
        assertThat(created.data().path("provider_name").asText()).isEqualTo("测试动物医院");
        assertThat(created.data().path("total_amount").asText()).isEqualTo("128.00");
        assertThat(created.data().path("coupon_discount").asText()).isEqualTo("30.00");
        assertThat(created.data().path("estimated_pay_amount").asText()).isEqualTo("98.00");
        assertThat(created.data().path("start_time").asText()).isEqualTo("09:00");
        assertThat(created.data().path("end_time").asText()).as("一格 30 分钟").isEqualTo("09:30");
        assertThat(created.data().path("redeem_code").isNull())
                .as("还没接单、也没到预约时间，核销码不该出现（ADR-0038 第二节）")
                .isTrue();

        // 号源被占住；券被**锁定**（不是消耗：核销才转已用，取消则释放）
        assertThat(slotBooked(shop.providerId(), shop.serviceId(), date, "09:00")).isEqualTo(1);
        Map<String, Object> coupon = couponRow(couponId);
        assertThat(((Number) coupon.get("status")).intValue()).isEqualTo(2);
        assertThat(((Number) coupon.get("locked_order_id")).longValue()).isEqualTo(orderId);

        // 订单行上没有任何收款字段（ADR-0036）：这一段是「钱在门店付」在库层面的证据
        Map<String, Object> row = orderRow(orderId);
        assertThat(row.keySet()).noneMatch(column -> column.contains("pay_")
                && !column.equals("estimated_pay_amount"));
        assertThat(row.keySet()).noneMatch(column -> column.contains("receiv") || column.contains("settle")
                || column.contains("refund") || column.contains("balance"));
    }

    @Test
    @DisplayName("价格区间越界 → 90001（运营收窄区间后，存量服务项也不能再下单）")
    void priceOutOfRangeRejected() {
        String licenseNo = "LIC-ORDER-002";
        long itemId = createTestCatalogItem("TX-001", "100.00", "200.00");
        ApprovedProvider provider = createApprovedProvider(licenseNo);
        setBusinessHours(provider.token());
        ApiClient.ApiCall listing = createListing(provider.token(), "TX-001", "150.00");
        assertCodeOk(listing, "选品定价");
        long serviceId = listing.data().path("id").asLong();
        assertCodeOk(api.post("/api/v1/admin/service-listings/" + serviceId + "/approve",
                Map.of("remark", "ok"), adminToken()), "上架审核");

        Shop shop = new Shop(provider.providerId(), provider.token(), serviceId, "TX-001", "150.00");
        UserActor user = createUser(nextPhone());
        long petId = api.createPet(user.token(), "豆豆");
        LocalDate date = tomorrow();

        assertCodeOk(placeOrder(user.token(), petId, shop, date, "09:00"), "区间内可以下单");

        // 运营把区间收窄到 100–120，服务者的 150 就落在区间外了
        narrowCatalogRange(itemId, "TX-001", "100.00", "120.00");
        ApiClient.ApiCall rejected = placeOrder(user.token(), petId, shop, date, "11:00");
        assertThat(rejected.status()).as("区间越界是业务结果，走 HTTP 200").isEqualTo(200);
        assertThat(rejected.code()).isEqualTo(90001);
        assertThat(rejected.message()).isEqualTo("价格须在¥100.00-¥120.00之间");
        assertThat(slotBooked(shop.providerId(), shop.serviceId(), date, "11:00"))
                .as("被拒的下单不该占住号源（事务回滚）")
                .isZero();
    }

    @Test
    @DisplayName("越权与不可选项：别人的宠物 → 40400；别的门店的服务项 / 没上架的服务项 → 40400")
    void createRejectsUnownedOrUnlisted() {
        Shop shop = createShop("LIC-ORDER-003", "128.00");
        Shop other = createShop("LIC-ORDER-004", "128.00");
        UserActor user = createUser(nextPhone());
        UserActor stranger = createUser(nextPhone());
        long strangerPet = api.createPet(stranger.token(), "别人的猫");
        long petId = api.createPet(user.token(), "豆豆");
        LocalDate date = tomorrow();

        ApiClient.ApiCall foreignPet = placeOrder(user.token(), strangerPet, shop, date, "09:00");
        assertThat(foreignPet.code()).as("宠物不属于我，按不存在处理（不区分「没这只」与「不是他的」）")
                .isEqualTo(40400);

        ApiClient.ApiCall foreignService = api.post("/api/v1/app/orders",
                new com.pethealth.api.order.OrderCreateRequest(petId, other.providerId(),
                        shop.serviceId(), date, "09:00", null, null), user.token());
        assertThat(foreignService.code()).as("服务项不属于这家门店").isEqualTo(40400);

        // 还没审核通过的服务项：对 C 端同样不存在
        ApprovedProvider pending = createApprovedProvider("LIC-ORDER-005");
        ApiClient.ApiCall pendingListing = createListing(pending.token(), SERVICE_CODE, "128.00");
        assertCodeOk(pendingListing, "选品定价（待审核）");
        ApiClient.ApiCall unlisted = api.post("/api/v1/app/orders",
                new com.pethealth.api.order.OrderCreateRequest(petId, pending.providerId(),
                        pendingListing.data().path("id").asLong(), date, "09:00", null, null), user.token());
        assertThat(unlisted.code()).as("没上架的服务项不能下单").isEqualTo(40400);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `order`", Integer.class))
                .as("被拒的下单不该留下任何订单")
                .isZero();
    }

    @Test
    @DisplayName("时段与日期：不在营业时间内 / 门店不营业 / 时段已开始 → 40001")
    void createRejectsUnbookableTime() {
        Shop shop = createShop("LIC-ORDER-006", "128.00");
        UserActor user = createUser(nextPhone());
        long petId = api.createPet(user.token(), "豆豆");
        LocalDate date = tomorrow();

        ApiClient.ApiCall outside = placeOrder(user.token(), petId, shop, date, "19:00");
        assertThat(outside.code()).isEqualTo(40001);
        assertThat(outside.message()).contains("营业时间");

        ApiClient.ApiCall misaligned = placeOrder(user.token(), petId, shop, date, "09:15");
        assertThat(misaligned.code()).as("时段按 30 分钟一格切，09:15 不是网格上的时段").isEqualTo(40001);

        ApiClient.ApiCall pastDate = placeOrder(user.token(), petId, shop, LocalDate.now().minusDays(1), "09:00");
        assertThat(pastDate.code()).isEqualTo(40001);

        // 门店没配营业时间：那天不可预约（不是「全天可约」）
        ApprovedProvider bare = createApprovedProvider("LIC-ORDER-007");
        ApiClient.ApiCall bareListing = createListing(bare.token(), SERVICE_CODE, "128.00");
        assertCodeOk(bareListing, "选品定价");
        assertCodeOk(api.post("/api/v1/admin/service-listings/" + bareListing.data().path("id").asLong()
                + "/approve", Map.of("remark", "ok"), adminToken()), "上架审核");
        ApiClient.ApiCall noHours = api.post("/api/v1/app/orders",
                new com.pethealth.api.order.OrderCreateRequest(petId, bare.providerId(),
                        bareListing.data().path("id").asLong(), date, "09:00", null, null), user.token());
        assertThat(noHours.code()).isEqualTo(40001);
        assertThat(noHours.message()).contains("不营业");

        // 时段已经开始：今天的第一格（00:00）**在任何时刻都已经开始**。
        // 这里曾经写 01:00——于是**在 00:00–01:00 之间跑这个用例，那一格就不再是「已经开始」**，
        // 断言变成 `expected: 40001 but was: 0`（2026-10-01 00:11 真红过一次：与代码无关，
        // 是墙上时钟走进了用例的假设里）。营业时间给成一整天，槽位就不会落到窗口外。
        assertCodeOk(api.put("/api/v1/provider/profile/business-hours",
                Map.of("hours", List.of(Map.of("day_of_week", LocalDate.now().getDayOfWeek().getValue(),
                        "open_time", "00:00", "close_time", "23:30"))), shop.token()), "改营业时间");
        ApiClient.ApiCall started = placeOrder(user.token(), petId, shop, LocalDate.now(), "00:00");
        assertThat(started.code()).isEqualTo(40001);
        assertThat(started.message()).contains("已经开始");
    }

    @Test
    @DisplayName("券：门槛不足 / 已被别的订单占用 / 别人的券 → 80001，面额大于总额时预估实付收口到 0.00")
    void createRejectsUnusableCoupon() {
        Shop shop = createShop("LIC-ORDER-008", "128.00");
        UserActor user = createUser(nextPhone());
        UserActor stranger = createUser(nextPhone());
        long petId = api.createPet(user.token(), "豆豆");
        LocalDate date = tomorrow();

        // 门槛 200 > 订单总额 128
        long highThreshold = issueCoupon(user.userId(), createSubsidyTemplate("CP-202", "30.00", "200.00"));
        ApiClient.ApiCall belowThreshold = placeOrder(user.token(), petId, shop, date, "09:00", highThreshold);
        assertThat(belowThreshold.code()).isEqualTo(80001);
        assertThat(belowThreshold.message()).contains("满");

        // 门槛 0：可以下单，券被锁定；同一张券再用到第二单 → 80001
        long couponId = issueCoupon(user.userId(), createSubsidyTemplate("CP-203", "30.00", "0.00"));
        assertCodeOk(placeOrder(user.token(), petId, shop, date, "10:00", couponId), "第一单用券");
        ApiClient.ApiCall secondUse = placeOrder(user.token(), petId, shop, date, "11:00", couponId);
        assertThat(secondUse.code()).isEqualTo(80001);
        assertThat(secondUse.message()).contains("其它订单");

        // 别人的券：按「不可用」处理（契约把「券不属于我」写在 80001 里），而且**抢不走**——
        // 锁定是带 user_id 条件的更新，别人锁过的券谁也别想再锁
        ApiClient.ApiCall foreign = placeOrder(stranger.token(), api.createPet(stranger.token(), "别人的猫"),
                shop, date, "12:00", couponId);
        assertThat(foreign.code()).isEqualTo(80001);
        assertThat(((Number) couponRow(couponId).get("locked_order_id")).longValue())
                .as("券仍锁在原来那一单上，没有被后来的请求抢走")
                .isEqualTo(jdbc.queryForObject("SELECT `id` FROM `order` WHERE `coupon_id` = ?",
                        Long.class, couponId));

        // 券面额大于总额时「预估实付」收口到 0.00，不出现负数
        Shop cheap = createShop("LIC-ORDER-009", "84.00");
        long bigFace = issueCoupon(user.userId(), createSubsidyTemplate("CP-204", "100.00", "0.00"));
        ApiClient.ApiCall floored = placeOrder(user.token(), petId, cheap, date, "09:00", bigFace);
        assertCodeOk(floored, "面额大于总额也允许下单");
        assertThat(floored.data().path("estimated_pay_amount").asText()).isEqualTo("0.00");
        assertThat(floored.data().path("coupon_discount").asText()).isEqualTo("100.00");
    }
}
