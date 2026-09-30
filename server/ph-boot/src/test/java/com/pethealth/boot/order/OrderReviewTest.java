package com.pethealth.boot.order;

import com.pethealth.boot.support.ApiClient;
import com.pethealth.order.domain.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评价晒单（第一版：评分 + 一句话）。
 *
 * <p>这一组用例盯的是**规则**，不是主流程：未完成不能评、只能评自己的单、一单一评、
 * 评分越界被拒、评价之后门店评分**真的变了**、REVIEW 分入账且第二次不再发、
 * 门店评价列表分页正确。每条断言都要能回答「它在什么情况下会红」——
 * 所以这里几乎不用 {@code isNotNull} 那种宽松断言：能断具体值就断具体值
 * （评分断言到 {@code "3.00"}、分断言到 {@code 5} 与 {@code review:{orderId}} 这个引用）。
 *
 * <p>评价成功之后有两件副作用，各有各的观测点：
 *
 * <ul>
 *   <li><b>门店评分</b>：{@code provider.rating} 是 ph-provider 的表，所以这里读库断言
 *       （跨模块读测试里是允许的），并顺带断言 C 端详情页看到的值——两条路径都要对；
 *   <li><b>积分</b>：走 AFTER_COMMIT 事件，所以断言落在 {@code point_record} 上
 *       （行为码与幂等引用一起断，才能证明「发的是评价那笔分」而不是别的行为）。
 * </ul>
 */
@DisplayName("评价晒单：一单一评、只有已完成能评、评分聚合与发放 REVIEW 分")
class OrderReviewTest extends OrderTestSupport {

    @Test
    @DisplayName("只有「已完成」的订单能评价：未完成一律 40900，且 message 说清当前状态")
    void onlyCompletedOrderCanBeReviewed() {
        Shop shop = createShop("LIC-REVIEW-001", "128.00");
        UserActor user = createUser(nextPhone());
        long petId = api.createPet(user.token(), "豆豆");
        long orderId = orderOk(user.token(), petId, shop, tomorrow(), "09:00");

        ApiClient.ApiCall pending = review(user.token(), orderId, 5, "很好");
        assertThat(pending.code()).as("待接单不能评价").isEqualTo(40900);
        assertThat(pending.message()).contains("待接单");

        assertCodeOk(api.post("/api/v1/provider/orders/" + orderId + "/accept", null, shop.token()), "接单");
        ApiClient.ApiCall booked = review(user.token(), orderId, 5, "很好");
        assertThat(booked.code()).as("已预约也不能评价").isEqualTo(40900);
        assertThat(booked.message()).contains("已预约");

        assertCodeOk(api.post("/api/v1/provider/orders/" + orderId + "/redeem", null, shop.token()), "核销");
        ApiClient.ApiCall inService = review(user.token(), orderId, 5, "很好");
        assertThat(inService.code()).as("履约中还是不能评价（服务还没做完）").isEqualTo(40900);
        assertThat(inService.message()).contains("履约中");

        assertThat(reviewRows(orderId)).as("三次被拒都没有落库").isZero();

        // 报工完成之后才能评
        complete(shop, user, orderId, petId);
        ApiClient.ApiCall reviewed = review(user.token(), orderId, 5, "很好");
        assertCodeOk(reviewed, "已完成订单可以评价");
        assertThat(reviewed.data().path("order_id").asLong()).isEqualTo(orderId);
        assertThat(reviewed.data().path("provider_id").asLong()).isEqualTo(shop.providerId());
        assertThat(reviewed.data().path("rating").asInt()).isEqualTo(5);
        assertThat(reviewed.data().path("content").asText()).isEqualTo("很好");
        assertThat(reviewed.data().path("created_at").asText()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
        assertThat(reviewRows(orderId)).isEqualTo(1);
    }

    @Test
    @DisplayName("只能评自己的订单：别人的订单一律 40400（与不存在同码）")
    void onlyOwnOrderCanBeReviewed() {
        Shop shop = createShop("LIC-REVIEW-002", "128.00");
        UserActor owner = createUser(nextPhone());
        UserActor stranger = createUser(nextPhone());
        long petId = api.createPet(owner.token(), "豆豆");
        long orderId = orderOk(owner.token(), petId, shop, tomorrow(), "09:00");
        complete(shop, owner, orderId, petId);

        ApiClient.ApiCall call = review(stranger.token(), orderId, 1, "不是我的单");
        assertThat(call.code()).as("别人的订单按不存在处理（免得用 order_id 探测）").isEqualTo(40400);
        assertThat(reviewRows(orderId)).as("别人的评价不会落库").isZero();

        // 不存在的订单同码（两种情况的答复必须一样，否则 40400 与 40900 就成了存在性探针）
        assertThat(review(owner.token(), 999_999L, 5, null).code()).isEqualTo(40400);
    }

    @Test
    @DisplayName("一单一评：第二次提交 40900（不是幂等成功），也不会再发一次 REVIEW 分")
    void oneReviewPerOrder() {
        Shop shop = createShop("LIC-REVIEW-003", "128.00");
        UserActor user = createUser(nextPhone());
        long petId = api.createPet(user.token(), "豆豆");
        long orderId = orderOk(user.token(), petId, shop, tomorrow(), "09:00");
        complete(shop, user, orderId, petId);

        ApiClient.ApiCall first = review(user.token(), orderId, 4, "还不错");
        assertCodeOk(first, "第一次评价");
        assertThat(reviewRows(orderId)).isEqualTo(1);
        assertThat(reviewPointCount(user.userId())).as("REVIEW 分入账一条").isEqualTo(1);
        assertThat(reviewPointAmount(user.userId())).as("评价晒单 5 分（V27 的种子值）").isEqualTo(5);
        assertThat(reviewSourceRefs(user.userId())).as("幂等引用点名「这是哪一单的评价」")
                .containsExactly("review:" + orderId);

        ApiClient.ApiCall second = review(user.token(), orderId, 1, "改成一星");
        assertThat(second.code()).as("重复提交是状态冲突，不是幂等成功").isEqualTo(40900);
        assertThat(second.message()).contains("已经评价过");
        assertThat(reviewRows(orderId)).as("唯一键兜底：仍然只有一条").isEqualTo(1);
        assertThat(reviewPointCount(user.userId())).as("第二次评价不再发分").isEqualTo(1);

        // 订单详情带上这条评价：界面据此显示「已评价」，而不是再给一次入口
        ApiClient.ApiCall detail = api.get("/api/v1/app/orders/" + orderId, user.token());
        assertCodeOk(detail, "订单详情");
        assertThat(detail.data().path("review").path("rating").asInt()).isEqualTo(4);
        assertThat(detail.data().path("review").path("content").asText()).isEqualTo("还不错");
        assertThat(detail.data().path("review").path("order_id").asLong()).isEqualTo(orderId);
    }

    @Test
    @DisplayName("评分越界或缺失 → 40001，且不落库；文字超长也是 40001")
    void ratingMustBeWithinOneToFive() {
        Shop shop = createShop("LIC-REVIEW-004", "128.00");
        UserActor user = createUser(nextPhone());
        long petId = api.createPet(user.token(), "豆豆");
        long orderId = orderOk(user.token(), petId, shop, tomorrow(), "09:00");
        complete(shop, user, orderId, petId);

        assertThat(review(user.token(), orderId, 0, null).code()).as("0 星越界").isEqualTo(40001);
        assertThat(review(user.token(), orderId, 6, null).code()).as("6 星越界").isEqualTo(40001);
        assertThat(api.post("/api/v1/app/orders/" + orderId + "/review", Map.of(), user.token()).code())
                .as("评分缺失（它是必填的）").isEqualTo(40001);
        assertThat(review(user.token(), orderId, 5, "长".repeat(501)).code())
                .as("文字超过 500 字").isEqualTo(40001);

        assertThat(reviewRows(orderId)).as("被拒的请求都不留下评价").isZero();
        assertThat(reviewPointCount(user.userId())).as("被拒的请求也不发分").isZero();

        // 边界值 1 与 5 都是合法的
        assertCodeOk(review(user.token(), orderId, 1, null), "1 星是合法下界");
    }

    @Test
    @DisplayName("评价之后 provider.rating 真的变了：3 星 → 3.0，再来一条 5 星 → 平均 4.0")
    void providerRatingFollowsReviews() {
        Shop shop = createShop("LIC-REVIEW-005", "128.00");
        UserActor first = createUser(nextPhone());
        UserActor second = createUser(nextPhone());
        long petId = api.createPet(first.token(), "豆豆");
        LocalDate date = tomorrow();

        // 没有任何评价时是数据库默认值：C 端看到 "5.00"——这个数**不代表**满分好评
        ApiClient.ApiCall before = api.get("/api/v1/app/providers/" + shop.providerId(), null);
        assertCodeOk(before, "看门店详情");
        assertThat(before.data().path("rating").asText()).isEqualTo("5.00");

        long firstOrder = orderOk(first.token(), petId, shop, date, "09:00");
        complete(shop, first, firstOrder, petId);
        assertCodeOk(review(first.token(), firstOrder, 3, "一般般"), "第一条评价（3 星）");

        assertThat(providerRating(shop.providerId())).as("评分被回写成这一条的平均分")
                .isEqualByComparingTo("3.0");
        ApiClient.ApiCall afterOne = api.get("/api/v1/app/providers/" + shop.providerId(), null);
        assertThat(afterOne.data().path("rating").asText())
                .as("C 端详情页看到的是同一个数（两位小数的字符串）").isEqualTo("3.00");

        long secondPet = api.createPet(second.token(), "毛毛");
        long secondOrder = orderOk(second.token(), secondPet, shop, date, "10:00");
        complete(shop, second, secondOrder, secondPet);
        assertCodeOk(review(second.token(), secondOrder, 5, "非常满意"), "第二条评价（5 星）");

        assertThat(providerRating(shop.providerId())).as("两条的平均分 (3+5)/2 = 4.0")
                .isEqualByComparingTo("4.0");
        ApiClient.ApiCall afterTwo = api.get("/api/v1/app/providers/" + shop.providerId(), null);
        assertThat(afterTwo.data().path("rating").asText()).isEqualTo("4.00");
    }

    @Test
    @DisplayName("门店评价列表：时间倒序 + 分页正确；不存在的门店返回空列表而不是 40400")
    void reviewListPagination() {
        Shop shop = createShop("LIC-REVIEW-006", "128.00");
        UserActor user = createUser(nextPhone());
        long petId = api.createPet(user.token(), "豆豆");
        LocalDate date = tomorrow();

        // 三条评价（同一个用户三单：REVIEW 每月上限 5 次，三单不会撞上限）
        String[] slots = {"09:00", "10:00", "11:00"};
        int[] ratings = {2, 4, 5};
        long[] orderIds = new long[3];
        for (int i = 0; i < 3; i++) {
            long orderId = orderOk(user.token(), petId, shop, date, slots[i]);
            complete(shop, user, orderId, petId);
            assertCodeOk(review(user.token(), orderId, ratings[i], "第 " + (i + 1) + " 单"), "第 " + (i + 1) + " 条评价");
            orderIds[i] = orderId;
        }

        ApiClient.ApiCall page1 = api.get("/api/v1/app/providers/" + shop.providerId() + "/reviews?page=1&page_size=2", user.token());
        assertCodeOk(page1, "第一页");
        assertThat(page1.data().path("total").asLong()).as("条数是全量，不是本页").isEqualTo(3);
        assertThat(page1.data().path("page").asLong()).isEqualTo(1);
        assertThat(page1.data().path("page_size").asLong()).isEqualTo(2);
        assertThat(page1.data().path("has_more").asBoolean()).as("还有一页").isTrue();
        assertThat(page1.data().path("list")).hasSize(2);
        // 最近提交的在前：第三条（最后一单）打头，且评分跟着订单走
        assertThat(page1.data().path("list").get(0).path("order_id").asLong()).isEqualTo(orderIds[2]);
        assertThat(page1.data().path("list").get(0).path("rating").asInt()).isEqualTo(5);
        assertThat(page1.data().path("list").get(1).path("order_id").asLong()).isEqualTo(orderIds[1]);
        assertThat(page1.data().path("list").get(1).path("rating").asInt()).isEqualTo(4);

        ApiClient.ApiCall page2 = api.get("/api/v1/app/providers/" + shop.providerId() + "/reviews?page=2&page_size=2", user.token());
        assertCodeOk(page2, "第二页");
        assertThat(page2.data().path("list")).hasSize(1);
        assertThat(page2.data().path("list").get(0).path("order_id").asLong()).isEqualTo(orderIds[0]);
        assertThat(page2.data().path("list").get(0).path("rating").asInt()).isEqualTo(2);
        assertThat(page2.data().path("has_more").asBoolean()).isFalse();

        // 别的门店看不到这些评价（按 provider_id 过滤，不是「返回全部」）
        Shop other = createShop("LIC-REVIEW-007", "128.00");
        ApiClient.ApiCall otherShop = api.get("/api/v1/app/providers/" + other.providerId() + "/reviews", user.token());
        assertCodeOk(otherShop, "另一家店的评价");
        assertThat(otherShop.data().path("total").asLong()).as("按门店过滤").isZero();
        assertThat(otherShop.data().path("list")).isEmpty();

        // 不存在的门店：200 + 空列表（契约写明；本接口不做可见性判定）
        ApiClient.ApiCall ghost = api.get("/api/v1/app/providers/999999/reviews", user.token());
        assertCodeOk(ghost, "不存在的门店");
        assertThat(ghost.data().path("total").asLong()).isZero();

        // 页码越界也是 40001（与列表接口同一口径）
        assertThat(api.get("/api/v1/app/providers/" + shop.providerId() + "/reviews?page=0", user.token()).code())
                .isEqualTo(40001);

        // 未登录没有评价可看（「内容面」要身份）：**40100 而不是空列表**——
        // 空列表会让未登录用户以为「这家店没人评过」，那是两个不同的回答
        ApiClient.ApiCall anonymous = api.get("/api/v1/app/providers/" + shop.providerId() + "/reviews", null);
        assertThat(anonymous.code()).as("未登录读评价列表").isEqualTo(40100);
    }

    // ---------------------------------------------------------------- 辅助

    /** 提交一条评价（未经断言的原始响应，由用例自己看码）。 */
    private ApiClient.ApiCall review(String token, long orderId, Integer rating, String content) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        if (rating != null) {
            body.put("rating", rating);
        }
        if (content != null) {
            body.put("content", content);
        }
        return api.post("/api/v1/app/orders/" + orderId + "/review", body, token);
    }

    /**
     * 把订单推到「已完成」：接单（还没接时）→ 核销 → 三道照片墙 → 报工。
     *
     * <p>按**当前状态**推进而不是每次重放全套动作：用例有时已经把订单推到履约中了
     * （比如验「履约中不能评价」那条），重放接单 / 核销会拿到 40900 而让辅助方法自己红——
     * 那会把「用例的断言」和「辅助方法的假设」搅在一起。
     */
    private void complete(Shop shop, UserActor user, long orderId, long petId) {
        int status = orderStatus(orderId);
        if (status == OrderStatus.PENDING_ACCEPT) {
            assertCodeOk(api.post("/api/v1/provider/orders/" + orderId + "/accept", null, shop.token()), "接单");
            status = OrderStatus.BOOKED;
        }
        if (status == OrderStatus.BOOKED) {
            assertCodeOk(api.post("/api/v1/provider/orders/" + orderId + "/redeem", null, shop.token()), "核销");
        }
        fillPhotoWall(shop.token(), user.token(), orderId, petId);
        assertCodeOk(api.post("/api/v1/provider/orders/" + orderId + "/report",
                Map.of("remark", "服务完成"), shop.token()), "报工");
    }

    private int orderStatus(long orderId) {
        return ((Number) orderRow(orderId).get("status")).intValue();
    }

    /** 这一单的评价条数（直读表：跨模块读在测试里是允许的，被验的链路正是写它的那条）。 */
    private int reviewRows(long orderId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `order_review` WHERE `order_id` = ? AND `is_deleted` = 0",
                Integer.class, orderId);
        return count == null ? 0 : count;
    }

    /** 门店当前的评分（`provider.rating` 归 ph-provider，这里看的是它真被回写了）。 */
    private java.math.BigDecimal providerRating(long providerId) {
        return jdbc.queryForObject("SELECT `rating` FROM `provider` WHERE `id` = ?",
                java.math.BigDecimal.class, providerId);
    }

    /** 该用户 REVIEW 行为的流水条数与合计分值（发分走 AFTER_COMMIT，所以看流水而不是余额）。 */
    private int reviewPointCount(long userId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM `point_record` "
                        + "WHERE `user_id` = ? AND `behavior_code` = 'REVIEW' AND `is_deleted` = 0",
                Integer.class, userId);
        return count == null ? 0 : count;
    }

    private int reviewPointAmount(long userId) {
        Integer sum = jdbc.queryForObject("SELECT COALESCE(SUM(`change_amount`), 0) FROM `point_record` "
                        + "WHERE `user_id` = ? AND `behavior_code` = 'REVIEW' AND `is_deleted` = 0",
                Integer.class, userId);
        return sum == null ? 0 : sum;
    }

    /**
     * 评价发分的幂等引用（`review:{orderId}`）。
     *
     * <p>断它而不是只断条数：条数为 1 也可能是「发了一笔别的行为的分」，
     * 而引用把「这笔分属于哪一单的评价」钉死——那正是幂等键的意义。
     */
    private List<String> reviewSourceRefs(long userId) {
        return jdbc.queryForList("SELECT `source_ref` FROM `point_record` "
                        + "WHERE `user_id` = ? AND `behavior_code` = 'REVIEW' AND `is_deleted` = 0",
                String.class, userId);
    }
}
