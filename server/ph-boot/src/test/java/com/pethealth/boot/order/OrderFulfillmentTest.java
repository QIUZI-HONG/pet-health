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
 * 履约链路：接单 → 核销 → 三道照片墙 → 报工（切片 #109 / #107；决策见 ADR-0038 第一、二节、
 * ADR-0040 第四节）。
 *
 * <p>三件必须由服务端兜住的事：**核销是原子的条件更新**（并发双击只有一个成功，
 * 重复核销 40900）、**订单状态与券的核销在同一个事务里**、**三个槽位各至少一张照片才允许报工**。
 */
@DisplayName("履约：接单 / 核销 / 三道照片墙 / 报工（切片 #109 / #107 / ADR-0038 / ADR-0040）")
class OrderFulfillmentTest extends OrderTestSupport {

    @Test
    @DisplayName("接单：只能从「待接单」推进，重复接单 40900；别人的订单 40400")
    void acceptOnlyFromPending() {
        Shop shop = createShop("LIC-FULFILL-001", "128.00");
        Shop other = createShop("LIC-FULFILL-002", "128.00");
        UserActor user = createUser(nextPhone());
        long petId = api.createPet(user.token(), "豆豆");
        long orderId = orderOk(user.token(), petId, shop, tomorrow(), "09:00");

        assertThat(api.post("/api/v1/provider/orders/" + orderId + "/accept", null, other.token()).code())
                .as("别的门店看不到这一单（越权与不存在同码）")
                .isEqualTo(40400);
        assertThat(api.post("/api/v1/provider/orders/" + orderId + "/redeem", null, other.token()).code())
                .isEqualTo(40400);

        ApiClient.ApiCall accepted = api.post("/api/v1/provider/orders/" + orderId + "/accept", null, shop.token());
        assertCodeOk(accepted, "接单");
        assertThat(accepted.data().path("status").asInt()).isEqualTo(1);
        assertThat(accepted.data().path("accepted_at").isNull()).isFalse();

        assertThat(api.post("/api/v1/provider/orders/" + orderId + "/accept", null, shop.token()).code())
                .as("重复接单是状态冲突（40900）")
                .isEqualTo(40900);
        // 接单即承诺：此后用户侧的同名动作变成「取消申请」，订单仍是已预约（ADR-0038 第一节）
        ApiClient.ApiCall requestCancel = api.post("/api/v1/app/orders/" + orderId + "/cancel",
                Map.of("reason", "临时有事"), user.token());
        assertCodeOk(requestCancel, "用户发起取消申请");
        assertThat(requestCancel.data().path("status").asInt()).isEqualTo(1);
        assertThat(requestCancel.data().path("cancel_request_status").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("核销：并发双击只有一个成功，重复核销 40900；券在同一事务里转「已核销」")
    void redeemIsAtomicAndCouponFollows() throws Exception {
        Shop shop = createShop("LIC-FULFILL-003", "128.00");
        UserActor user = createUser(nextPhone());
        long petId = api.createPet(user.token(), "豆豆");
        long couponId = issueCoupon(user.userId(), createSubsidyTemplate("CP-301", "30.00", "0.00"));
        long orderId = orderOk(user.token(), petId, shop, tomorrow(), "09:00");
        assertCodeOk(api.post("/api/v1/provider/orders/" + orderId + "/accept", null, shop.token()), "接单");

        // 并发核销：4 个请求同时打，只有一个能改到行
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
                        ApiClient.ApiCall call = api.post("/api/v1/provider/orders/" + orderId + "/redeem",
                                null, shop.token());
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
        assertThat(unexpected).isEmpty();
        assertThat(success.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(threads - 1);
        assertThat(((Number) orderRow(orderId).get("status")).intValue()).isEqualTo(2);

        // 再核销一次：状态冲突（不是幂等成功）
        ApiClient.ApiCall again = api.post("/api/v1/provider/orders/" + orderId + "/redeem", null, shop.token());
        assertThat(again.code()).isEqualTo(40900);
        assertThat(again.status()).isEqualTo(409);
    }

    @Test
    @DisplayName("带券核销：券在同一个事务里转「已核销」；券不可核销时订单状态回滚")
    void redeemCouponInSameTransaction() {
        Shop shop = createShop("LIC-FULFILL-004", "128.00");
        UserActor user = createUser(nextPhone());
        long petId = api.createPet(user.token(), "豆豆");
        LocalDate date = tomorrow();

        long goodCoupon = issueCoupon(user.userId(), createSubsidyTemplate("CP-302", "30.00", "0.00"));
        long orderId = orderOk(user.token(), petId, shop, date, "09:00", goodCoupon);
        assertCodeOk(api.post("/api/v1/provider/orders/" + orderId + "/accept", null, shop.token()), "接单");
        ApiClient.ApiCall redeemed = api.post("/api/v1/provider/orders/" + orderId + "/redeem", null, shop.token());
        assertCodeOk(redeemed, "核销");
        assertThat(redeemed.data().path("coupon").path("status").asInt()).isEqualTo(3);
        Map<String, Object> coupon = couponRow(goodCoupon);
        assertThat(((Number) coupon.get("status")).intValue()).isEqualTo(3);
        assertThat(((Number) coupon.get("redeemed_order_id")).longValue()).isEqualTo(orderId);

        // 第二单：把券改成已过期（测试直接改库造场景），核销时券侧拒绝 80001，订单必须退回「已预约」
        long staleCoupon = issueCoupon(user.userId(), createSubsidyTemplate("CP-303", "30.00", "0.00"));
        long secondOrder = orderOk(user.token(), petId, shop, date, "10:00", staleCoupon);
        assertCodeOk(api.post("/api/v1/provider/orders/" + secondOrder + "/accept", null, shop.token()), "接单");
        jdbc.update("UPDATE `coupon` SET `valid_until` = DATE_SUB(NOW(), INTERVAL 1 DAY) WHERE `id` = ?",
                staleCoupon);
        ApiClient.ApiCall rejected = api.post("/api/v1/provider/orders/" + secondOrder + "/redeem",
                null, shop.token());
        assertThat(rejected.code()).as("券的问题按券的码回（HTTP 200）").isEqualTo(80001);
        assertThat(rejected.status()).isEqualTo(200);
        assertThat(((Number) orderRow(secondOrder).get("status")).intValue())
                .as("券核销失败要让订单状态一起回滚——两者在同一个事务里")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("核销码：到预约时间才在 C 端可见，服务者侧始终不下发")
    void redeemCodeVisibility() {
        Shop shop = createShop("LIC-FULFILL-005", "128.00");
        UserActor user = createUser(nextPhone());
        long petId = api.createPet(user.token(), "豆豆");
        LocalDate date = tomorrow();
        long orderId = orderOk(user.token(), petId, shop, date, "09:00");

        assertThat(api.get("/api/v1/app/orders/" + orderId, user.token()).data().path("redeem_code").isNull())
                .as("还没接单：没有核销码").isTrue();
        assertCodeOk(api.post("/api/v1/provider/orders/" + orderId + "/accept", null, shop.token()), "接单");
        assertThat(api.get("/api/v1/app/orders/" + orderId, user.token()).data().path("redeem_code").isNull())
                .as("已接单但还没到预约时间：仍然不可见（太早泄漏等于把凭证挂在外面）").isTrue();

        // 把预约时间挪到过去，等价于「预约时间到了」
        jdbc.update("UPDATE `order` SET `appointment_date` = CURDATE(), `start_time` = '00:00', "
                + "`end_time` = '01:00' WHERE `id` = ?", orderId);
        ApiClient.ApiCall detail = api.get("/api/v1/app/orders/" + orderId, user.token());
        String code = detail.data().path("redeem_code").asText();
        assertThat(code).matches("\\d{6}");
        assertThat(detail.data().path("photo_wall").path("slots")).as("三个槽位恒在").hasSize(3);
        assertThat(detail.data().path("photo_wall").path("reportable").asBoolean()).isFalse();
        assertThat(detail.data().path("photo_wall").path("missing_slots")).hasSize(3);

        // 服务者侧的详情里没有核销码——门店凭用户出示的码定位订单，不需要知道码本身
        ApiClient.ApiCall providerDetail = api.get("/api/v1/provider/orders/" + orderId, shop.token());
        assertCodeOk(providerDetail, "门店看订单详情");
        assertThat(providerDetail.data().has("redeem_code")).as("服务者侧不下发核销码").isFalse();
        assertThat(providerDetail.data().path("user_phone").asText()).matches("\\d{3}\\*{4}\\d{4}");
        assertThat(providerDetail.data().path("user_nickname").asText()).contains("*");
    }

    @Test
    @DisplayName("报工：三道照片墙缺一不可，缺哪一道要说清；补齐后报工完成")
    void reportRequiresAllThreeSlots() {
        Shop shop = createShop("LIC-FULFILL-006", "128.00");
        UserActor user = createUser(nextPhone());
        long petId = api.createPet(user.token(), "豆豆");
        long orderId = orderInService(user.token(), shop.token(), petId, shop, tomorrow(), "09:00");

        ApiClient.ApiCall tooEarly = api.post("/api/v1/provider/orders/" + orderId + "/report",
                Map.of("remark", "做完了"), shop.token());
        assertThat(tooEarly.code()).isEqualTo(40900);
        assertThat(tooEarly.message()).contains("接宠检查").contains("服务防护").contains("取宠对比");

        // 只补两道：还是拒绝，且只点名缺的那一道
        for (int slot : List.of(1, 2)) {
            assertCodeOk(saveSlot(shop.token(), orderId, slot,
                    List.of(uploadCarePhoto(user.token(), petId)), "已拍"), "保存槽位 " + slot);
        }
        ApiClient.ApiCall missingOne = api.post("/api/v1/provider/orders/" + orderId + "/report",
                Map.of("remark", "做完了"), shop.token());
        assertThat(missingOne.code()).isEqualTo(40900);
        assertThat(missingOne.message()).contains("取宠对比").doesNotContain("接宠检查");

        ApiClient.ApiCall wall = api.get("/api/v1/provider/orders/" + orderId, shop.token());
        assertThat(wall.data().path("photo_wall").path("reportable").asBoolean()).isFalse();
        assertThat(wall.data().path("photo_wall").path("missing_slots")).hasSize(1);
        assertThat(wall.data().path("photo_wall").path("missing_slots").get(0).asInt()).isEqualTo(3);
        assertThat(wall.data().path("photo_wall").path("slots").get(0).path("satisfied").asBoolean()).isTrue();

        assertCodeOk(saveSlot(shop.token(), orderId, 3,
                List.of(uploadCarePhoto(user.token(), petId)), "取宠对比"), "保存槽位 3");
        ApiClient.ApiCall reported = api.post("/api/v1/provider/orders/" + orderId + "/report",
                Map.of("remark", "服务完成"), shop.token());
        assertCodeOk(reported, "报工");
        assertThat(reported.data().path("status").asInt()).isEqualTo(3);
        assertThat(reported.data().path("report_remark").asText()).isEqualTo("服务完成");
        assertThat(reported.data().path("photo_wall").path("reportable").asBoolean()).isTrue();
        assertThat(orderRow(orderId).get("redeem_code_active"))
                .as("进入终态后核销码让出唯一键（6 位码位不该被历史订单吃光）")
                .isNull();
        assertThat(orderRow(orderId).get("redeem_code")).as("码本身仍留着可查可追").isNotNull();

        // 报工提交即固化（ADR-0049 §一）：用 40901 与「还没到能写的状态」分开，
        // 而且 message 要说清唯一的路是运营干预
        ApiClient.ApiCall finalized = saveSlot(shop.token(), orderId, 1, List.of(), null);
        assertThat(finalized.code()).isEqualTo(40901);
        assertThat(finalized.message()).contains("运营干预");
        assertThat(api.post("/api/v1/provider/orders/" + orderId + "/report", Map.of(), shop.token()).code())
                .isEqualTo(40900);
    }

    @Test
    @DisplayName("照片墙：只在「履约中」可写；整体替换；重复挂载与错宠物都被拒")
    void photoWallRules() {
        Shop shop = createShop("LIC-FULFILL-007", "128.00");
        Shop otherShop = createShop("LIC-FULFILL-008", "128.00");
        UserActor user = createUser(nextPhone());
        long petId = api.createPet(user.token(), "豆豆");
        long otherPet = api.createPet(user.token(), "另一只");
        LocalDate date = tomorrow();
        long orderId = orderOk(user.token(), petId, shop, date, "09:00");

        ApiClient.ApiCall notYet = saveSlot(shop.token(), orderId, 1,
                List.of(uploadCarePhoto(user.token(), petId)), "太早");
        assertThat(notYet.code()).as("待接单 / 已预约不能写照片墙").isEqualTo(40900);

        assertCodeOk(api.post("/api/v1/provider/orders/" + orderId + "/accept", null, shop.token()), "接单");
        assertCodeOk(api.post("/api/v1/provider/orders/" + orderId + "/redeem", null, shop.token()), "核销");

        // 不是 care 用途的照片（用户的档案照片）不能当服务留痕
        ApiClient.ApiCall profile = api.post("/api/v1/app/files/presign", Map.of(
                "biz_type", "profile", "pet_id", petId, "items", List.of(Map.of("mime", "image/jpeg"))),
                user.token());
        assertCodeOk(profile, "申请档案照片凭证");
        api.putBinary(profile.data().get(0).path("upload_url").asText(), jpeg(400, 300));
        long profileFile = profile.data().get(0).path("file_id").asLong();
        assertThat(saveSlot(shop.token(), orderId, 1, List.of(profileFile), null).code()).isEqualTo(40400);

        // 别的宠物的 care 照片也不行（「不属于本订单」最实在的一种）
        long wrongPetFile = uploadCarePhoto(user.token(), otherPet);
        assertThat(saveSlot(shop.token(), orderId, 1, List.of(wrongPetFile), null).code()).isEqualTo(40400);

        // 正常写入 + 多图排序 + 备注
        long first = uploadCarePhoto(user.token(), petId);
        long second = uploadCarePhoto(user.token(), petId);
        ApiClient.ApiCall saved = saveSlot(shop.token(), orderId, 1, List.of(first, second), "接宠时皮肤有红点");
        assertCodeOk(saved, "保存槽位 1");
        assertThat(saved.data().path("slots").get(0).path("photos")).hasSize(2);
        assertThat(saved.data().path("slots").get(0).path("remark").asText()).isEqualTo("接宠时皮肤有红点");
        assertThat(saved.data().path("slots").get(0).path("photos").get(0).path("url").asText())
                .startsWith("/api/v1/open/files/");

        // 同一个 file_id 传两次 → 40001；超过 9 张 → 40001
        assertThat(saveSlot(shop.token(), orderId, 2, List.of(first, first), null).code()).isEqualTo(40001);
        List<Long> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ten.add(uploadCarePhoto(user.token(), petId));
        }
        assertThat(saveSlot(shop.token(), orderId, 2, ten, null).code()).isEqualTo(40001);

        // 已经挂在本单另一个槽位上 → 40001；挂到别的订单上 → 40400
        assertThat(saveSlot(shop.token(), orderId, 2, List.of(first), null).code()).isEqualTo(40001);
        long otherOrder = orderInService(user.token(), otherShop.token(), petId, otherShop, date, "09:00");
        assertThat(saveSlot(otherShop.token(), otherOrder, 1, List.of(first), null).code()).isEqualTo(40400);

        // 整体替换：传新的一组，旧的那张就不在墙上了（而它仍存在于文件域）
        ApiClient.ApiCall replaced = saveSlot(shop.token(), orderId, 1, List.of(second), "换成这张");
        assertCodeOk(replaced, "整体替换");
        assertThat(replaced.data().path("slots").get(0).path("photos")).hasSize(1);
        assertThat(replaced.data().path("slots").get(0).path("photos").get(0).path("id").asLong())
                .isEqualTo(second);
        assertThat(replaced.data().path("slots").get(0).path("satisfied").asBoolean()).isTrue();

        // 清空 → 该槽位不满足，整面墙不可报工
        ApiClient.ApiCall cleared = saveSlot(shop.token(), orderId, 1, List.of(), null);
        assertCodeOk(cleared, "清空槽位 1");
        assertThat(cleared.data().path("slots").get(0).path("satisfied").asBoolean()).isFalse();
        assertThat(cleared.data().path("reportable").asBoolean()).isFalse();
        assertThat(cleared.data().path("missing_slots")).as("三道全缺（其余两道本来就没传）").hasSize(3);
        assertThat(cleared.data().path("missing_slots").get(0).asInt()).isEqualTo(1);

        // 别人的订单不能写（越权与不存在同码）
        assertThat(saveSlot(otherShop.token(), orderId, 1, List.of(), null).code()).isEqualTo(40400);
    }

    @Test
    @DisplayName("门店取消：待接单 / 已预约可取消并释放号源与券；履约中与终态 40900")
    void providerCancelReleasesResources() {
        Shop shop = createShop("LIC-FULFILL-009", "128.00");
        UserActor user = createUser(nextPhone());
        long petId = api.createPet(user.token(), "豆豆");
        LocalDate date = tomorrow();
        long couponId = issueCoupon(user.userId(), createSubsidyTemplate("CP-304", "30.00", "0.00"));

        long orderId = orderOk(user.token(), petId, shop, date, "09:00", couponId);
        ApiClient.ApiCall cancelled = api.post("/api/v1/provider/orders/" + orderId + "/cancel",
                Map.of("reason", "设备检修，无法履约"), shop.token());
        assertCodeOk(cancelled, "门店取消");
        assertThat(cancelled.data().path("status").asInt()).isEqualTo(4);
        assertThat(cancelled.data().path("cancelled_by").asInt()).isEqualTo(2);
        assertThat(slotBooked(shop.providerId(), shop.serviceId(), date, "09:00")).isZero();
        assertThat(((Number) couponRow(couponId).get("status")).intValue())
                .as("取消释放券回「待使用」").isEqualTo(1);

        // 门店单方取消要给用户发站内消息（ADR-0049 §七），理由写进正文
        ApiClient.ApiCall messages = api.get("/api/v1/app/messages?kind=2", user.token());
        assertCodeOk(messages, "看我的消息");
        assertThat(messages.data().path("list")).hasSize(1);
        assertThat(messages.data().path("list").get(0).path("title").asText()).isEqualTo("订单已取消");
        assertThat(messages.data().path("list").get(0).path("content").asText()).contains("设备检修");

        ApiClient.ApiCall noReason = api.post("/api/v1/provider/orders/" + orderId + "/cancel",
                Map.of(), shop.token());
        assertThat(noReason.code()).as("门店单方取消必须填理由（ADR-0049 §七）").isEqualTo(40001);
        assertThat(api.post("/api/v1/provider/orders/" + orderId + "/cancel",
                Map.of("reason", "再取消一次"), shop.token()).code())
                .as("已取消是终态，重复取消 40900").isEqualTo(40900);

        // 履约中不可取消：服务已经开始了，只能报工完成或由运营干预
        long inService = orderInService(user.token(), shop.token(), petId, shop, date, "10:00");
        ApiClient.ApiCall late = api.post("/api/v1/provider/orders/" + inService + "/cancel",
                Map.of("reason", "想做也做不了了"), shop.token());
        assertThat(late.code()).isEqualTo(40900);
        assertThat(late.message()).contains("履约中");
    }
}
