package com.pethealth.boot.order;

import com.pethealth.boot.support.ApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 双侧取消（切片 #109；决策见 ADR-0038 第一节）。
 *
 * <p>规则**按阶段分**：`待接单` 用户自由取消；`已预约` 用户发起取消需门店同意（拒绝必填理由）；
 * `履约中` 不可取消；`已完成 / 已取消` 是终态。取消一律**没有资金动作**（ADR-0036：没有退款单）。
 */
@DisplayName("双侧取消：直接取消 / 申请 / 同意 / 拒绝（切片 #109 / ADR-0038 第一节）")
class OrderCancelTest extends OrderTestSupport {

    @Test
    @DisplayName("待接单直接取消：释放号源与券、记下取消人与时间；重复取消 40900")
    void userCancelsPendingOrder() {
        Shop shop = createShop("LIC-CANCEL-001", "128.00");
        UserActor user = createUser(nextPhone());
        long petId = api.createPet(user.token(), "豆豆");
        LocalDate date = tomorrow();
        long couponId = issueCoupon(user.userId(), createSubsidyTemplate("CP-401", "30.00", "0.00"));
        long orderId = orderOk(user.token(), petId, shop, date, "09:00", couponId);
        assertThat(slotBooked(shop.providerId(), shop.serviceId(), date, "09:00")).isEqualTo(1);

        ApiClient.ApiCall cancelled = api.post("/api/v1/app/orders/" + orderId + "/cancel",
                Map.of("reason", "临时有事去不了"), user.token());
        assertCodeOk(cancelled, "取消");
        assertThat(cancelled.data().path("status").asInt()).isEqualTo(4);
        assertThat(cancelled.data().path("cancelled_by").asInt()).isEqualTo(1);
        assertThat(cancelled.data().path("cancel_reason").asText()).isEqualTo("临时有事去不了");
        assertThat(cancelled.data().path("cancelled_at").isNull()).isFalse();
        assertThat(cancelled.data().path("redeem_code").isNull()).as("取消后不再下发核销码").isTrue();

        assertThat(slotBooked(shop.providerId(), shop.serviceId(), date, "09:00")).as("号源被释放").isZero();
        assertThat(((Number) couponRow(couponId).get("status")).intValue()).as("券回到待使用").isEqualTo(1);
        assertThat(couponRow(couponId).get("locked_order_id")).isNull();

        ApiClient.ApiCall again = api.post("/api/v1/app/orders/" + orderId + "/cancel", Map.of(), user.token());
        assertThat(again.code()).as("已取消是终态，重复取消 40900").isEqualTo(40900);

        // 号源释放之后别人能约进同一时段
        UserActor other = createUser(nextPhone());
        long otherPet = api.createPet(other.token(), "别人家的猫");
        assertCodeOk(placeOrder(other.token(), otherPet, shop, date, "09:00"), "释放后可以再约");
    }

    @Test
    @DisplayName("已预约阶段的取消申请：门店同意 → 已取消；拒绝 → 仍是已预约且理由必填")
    void cancelRequestApproveAndReject() {
        Shop shop = createShop("LIC-CANCEL-002", "128.00");
        UserActor user = createUser(nextPhone());
        long petId = api.createPet(user.token(), "豆豆");
        LocalDate date = tomorrow();

        // ① 同意：订单转已取消，号源释放
        long first = orderOk(user.token(), petId, shop, date, "09:00");
        assertCodeOk(api.post("/api/v1/provider/orders/" + first + "/accept", null, shop.token()), "接单");
        ApiClient.ApiCall requested = api.post("/api/v1/app/orders/" + first + "/cancel",
                Map.of("reason", "宠物生病了"), user.token());
        assertCodeOk(requested, "发起取消申请");
        assertThat(requested.data().path("status").asInt()).as("申请阶段订单仍是已预约").isEqualTo(1);
        assertThat(requested.data().path("cancel_request_status").asInt()).isEqualTo(1);
        assertThat(requested.data().path("cancel_requested_at").isNull()).isFalse();

        ApiClient.ApiCall repeated = api.post("/api/v1/app/orders/" + first + "/cancel",
                Map.of("reason", "再提交一次"), user.token());
        assertThat(repeated.code()).as("重复提交申请是冲突").isEqualTo(40900);

        ApiClient.ApiCall approved = api.post("/api/v1/provider/orders/" + first + "/cancel/approve",
                null, shop.token());
        assertCodeOk(approved, "门店同意");
        assertThat(approved.data().path("status").asInt()).isEqualTo(4);
        assertThat(approved.data().path("cancel_request_status").asInt()).isEqualTo(2);
        assertThat(slotBooked(shop.providerId(), shop.serviceId(), date, "09:00")).isZero();
        assertThat(api.post("/api/v1/provider/orders/" + first + "/cancel/approve", null, shop.token()).code())
                .as("已经处理过的申请不能再同意").isEqualTo(40900);

        // ② 拒绝：理由必填（不填 40001），拒绝后订单仍是已预约
        long second = orderOk(user.token(), petId, shop, date, "10:00");
        assertCodeOk(api.post("/api/v1/provider/orders/" + second + "/accept", null, shop.token()), "接单");
        assertCodeOk(api.post("/api/v1/app/orders/" + second + "/cancel", Map.of("reason", "有事"),
                user.token()), "发起取消申请");

        ApiClient.ApiCall blank = api.post("/api/v1/provider/orders/" + second + "/cancel/reject",
                Map.of("reason", " "), shop.token());
        assertThat(blank.code()).as("拒绝理由必填（ADR-0038 第一节点名要求）").isEqualTo(40001);

        ApiClient.ApiCall rejected = api.post("/api/v1/provider/orders/" + second + "/cancel/reject",
                Map.of("reason", "已备好耗材，请按约到店"), shop.token());
        assertCodeOk(rejected, "门店拒绝");
        assertThat(rejected.data().path("status").asInt()).as("拒绝后仍是已预约").isEqualTo(1);
        assertThat(rejected.data().path("cancel_request_status").asInt()).isEqualTo(3);
        assertThat(rejected.data().path("cancel_rejected_reason").asText()).isEqualTo("已备好耗材，请按约到店");
        assertThat(slotBooked(shop.providerId(), shop.serviceId(), date, "10:00")).as("号源没被释放").isEqualTo(1);

        // 拒绝之后用户还可以再申请一次（ADR 没说禁止；理由与原来不同）
        assertCodeOk(api.post("/api/v1/app/orders/" + second + "/cancel", Map.of("reason", "还是去不了"),
                user.token()), "再次申请");

        // 没有待处理申请时，同意 / 拒绝都是 40900
        long third = orderOk(user.token(), petId, shop, date, "11:00");
        assertCodeOk(api.post("/api/v1/provider/orders/" + third + "/accept", null, shop.token()), "接单");
        assertThat(api.post("/api/v1/provider/orders/" + third + "/cancel/approve", null, shop.token()).code())
                .isEqualTo(40900);
        assertThat(api.post("/api/v1/provider/orders/" + third + "/cancel/reject",
                Map.of("reason", "理由"), shop.token()).code()).isEqualTo(40900);
    }

    @Test
    @DisplayName("门店直接取消已预约的订单：订单转已取消，申请随之按已同意收场；券释放")
    void providerCancelClosesPendingRequest() {
        Shop shop = createShop("LIC-CANCEL-003", "128.00");
        UserActor user = createUser(nextPhone());
        long petId = api.createPet(user.token(), "豆豆");
        LocalDate date = tomorrow();
        long couponId = issueCoupon(user.userId(), createSubsidyTemplate("CP-402", "30.00", "0.00"));
        long orderId = orderOk(user.token(), petId, shop, date, "09:00", couponId);
        assertCodeOk(api.post("/api/v1/provider/orders/" + orderId + "/accept", null, shop.token()), "接单");
        assertCodeOk(api.post("/api/v1/app/orders/" + orderId + "/cancel", Map.of("reason", "有事"),
                user.token()), "发起取消申请");

        ApiClient.ApiCall cancelled = api.post("/api/v1/provider/orders/" + orderId + "/cancel",
                Map.of("reason", "当日设备检修"), shop.token());
        assertCodeOk(cancelled, "门店取消");
        assertThat(cancelled.data().path("status").asInt()).isEqualTo(4);
        assertThat(cancelled.data().path("cancelled_by").asInt()).isEqualTo(2);
        assertThat(cancelled.data().path("cancel_request_status").asInt())
                .as("结果与「同意申请」一致，申请按已同意收场").isEqualTo(2);
        assertThat(slotBooked(shop.providerId(), shop.serviceId(), date, "09:00")).isZero();
        assertThat(((Number) couponRow(couponId).get("status")).intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("越权：别人的订单取消 / 查看一律 40400（C 端与服务者侧各自把关）")
    void crossAccountAccessDenied() {
        Shop shop = createShop("LIC-CANCEL-004", "128.00");
        Shop otherShop = createShop("LIC-CANCEL-005", "128.00");
        UserActor owner = createUser(nextPhone());
        UserActor stranger = createUser(nextPhone());
        long petId = api.createPet(owner.token(), "豆豆");
        long orderId = orderOk(owner.token(), petId, shop, tomorrow(), "09:00");

        assertThat(api.get("/api/v1/app/orders/" + orderId, stranger.token()).code()).isEqualTo(40400);
        assertThat(api.post("/api/v1/app/orders/" + orderId + "/cancel", Map.of(), stranger.token()).code())
                .isEqualTo(40400);
        assertThat(api.get("/api/v1/provider/orders/" + orderId, otherShop.token()).code()).isEqualTo(40400);
        assertThat(api.post("/api/v1/provider/orders/" + orderId + "/accept", null, otherShop.token()).code())
                .isEqualTo(40400);
        assertThat(api.post("/api/v1/provider/orders/" + orderId + "/report", Map.of(), otherShop.token()).code())
                .isEqualTo(40400);

        // C 端的列表只出自己的单
        UserActor other = createUser(nextPhone());
        long otherPet = api.createPet(other.token(), "别人家的猫");
        orderOk(other.token(), otherPet, shop, tomorrow(), "10:00");
        ApiClient.ApiCall mine = api.get("/api/v1/app/orders", owner.token());
        assertThat(mine.data().path("list")).hasSize(1);
        assertThat(mine.data().path("list").get(0).path("id").asLong()).isEqualTo(orderId);

        // 服务者侧只出自己的单
        ApiClient.ApiCall providerOrders = api.get("/api/v1/provider/orders", shop.token());
        assertThat(providerOrders.data().path("list")).hasSize(2);
        ApiClient.ApiCall otherOrders = api.get("/api/v1/provider/orders", otherShop.token());
        assertThat(otherOrders.data().path("list")).isEmpty();
    }

    @Test
    @DisplayName("核销定位：按订单号（含后几位）、完整手机号、6 位核销码都能找到本店的订单")
    void providerSearchesByKeyword() {
        Shop shop = createShop("LIC-CANCEL-006", "128.00");
        String phone = nextPhone();
        UserActor user = createUser(phone);
        long petId = api.createPet(user.token(), "豆豆");
        LocalDate date = tomorrow();
        long orderId = orderOk(user.token(), petId, shop, date, "09:00");
        String orderNo = api.get("/api/v1/app/orders/" + orderId, user.token()).data().path("order_no").asText();
        assertCodeOk(api.post("/api/v1/provider/orders/" + orderId + "/accept", null, shop.token()), "接单");
        // 把预约时间挪过去，核销码才可见（顺便验一次可见性）
        jdbc.update("UPDATE `order` SET `appointment_date` = CURDATE(), `start_time` = '00:00' WHERE `id` = ?",
                orderId);
        String redeemCode = api.get("/api/v1/app/orders/" + orderId, user.token())
                .data().path("redeem_code").asText();

        assertThat(api.get("/api/v1/provider/orders?keyword=" + redeemCode, shop.token())
                .data().path("list")).as("按 6 位核销码精确匹配").hasSize(1);
        assertThat(api.get("/api/v1/provider/orders?keyword=" + orderNo.substring(8), shop.token())
                .data().path("list")).as("订单号按包含匹配（用户常常只报后几位）").hasSize(1);
        assertThat(api.get("/api/v1/provider/orders?keyword=" + phone, shop.token())
                .data().path("list")).as("按完整手机号等值匹配").hasSize(1);
        assertThat(api.get("/api/v1/provider/orders?keyword=99999999", shop.token())
                .data().path("list")).as("没找到就是空").isEmpty();
        assertThat(api.get("/api/v1/provider/orders?appointment_date=" + LocalDate.now(), shop.token())
                .data().path("list")).hasSize(1);
        assertThat(api.get("/api/v1/provider/orders?status=0", shop.token()).data().path("list")).isEmpty();
    }
}
