package com.pethealth.order.service;

import com.pethealth.api.app.UserProfile;
import com.pethealth.api.order.OrderSummaryView;
import com.pethealth.api.order.OrderView;
import com.pethealth.api.order.ProviderOrderSummaryView;
import com.pethealth.api.order.ProviderOrderView;
import com.pethealth.api.privilege.CouponDtos.CouponView;
import com.pethealth.common.time.AppTime;
import com.pethealth.common.util.Masking;
import com.pethealth.order.domain.OrderStatus;
import com.pethealth.order.domain.ServiceOrder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 订单实体 → 契约视图的装配（两端的四种视图都在这里，别处不要再拼一遍）。
 *
 * <p>三条集中在这里的口径：
 *
 * <ol>
 *   <li><b>金额是字符串</b>（{@code "128.00"}）：小数在 JS 里会丢精度（ADR-0011），
 *       所以 {@code BigDecimal} 一律 {@code toPlainString()}。**没有收款字段**——
 *       订单上的 {@code estimatedPayAmount} 是展示用的预估（ADR-0036）；
 *   <li><b>核销码的可见性</b>：状态在「已预约 / 履约中」**且**已到预约时间才给（ADR-0038 第二节）。
 *       服务者侧的视图里**始终没有它**（契约写死）——门店凭用户出示的码定位订单，
 *       不需要知道码本身，不知道就更不可能被记下来复用；
 *   <li><b>批量取名字，不按行查</b>：一页 20 条订单只调一次门店名 / 一次预约人资料 / 一次券视图。
 *       N+1 在这里不是理论问题——一页 20 单就是 60 次跨模块调用。
 * </ol>
 *
 * <p>外部数据（门店名、预约人、宠物、券）查不到时**留空而不是编一个值**：
 * 这些是现取的展示字段，缺一列比错一列好；订单本身的数据都有快照（迁移 V29）。
 */
@Component
public class OrderViews {

    /** 宠物物种没取到时的占位，与 {@code pet_species} 同码（0 表示未知）。 */
    private static final int SPECIES_UNKNOWN = 0;

    private final ProviderFacts providerFacts;
    private final OrderParties parties;
    private final OrderPhotoWallService photoWall;
    private final OrderReviewService reviews;

    public OrderViews(ProviderFacts providerFacts, OrderParties parties, OrderPhotoWallService photoWall,
                      OrderReviewService reviews) {
        this.providerFacts = providerFacts;
        this.parties = parties;
        this.photoWall = photoWall;
        this.reviews = reviews;
    }

    // ---------------------------------------------------------------- C 端

    /** 我的订单列表（批量装配：一页只取一次门店名）。 */
    public List<OrderSummaryView> mineSummaries(List<ServiceOrder> orders) {
        Map<Long, String> providerNames = providerFacts.providerNames(providerIdsOf(orders));
        List<OrderSummaryView> views = new ArrayList<>(orders.size());
        for (ServiceOrder order : orders) {
            views.add(new OrderSummaryView(
                    order.getId(),
                    order.getOrderNo(),
                    order.getStatus(),
                    order.getProviderId(),
                    providerNames.get(order.getProviderId()),
                    order.getServiceId(),
                    order.getServiceName(),
                    order.getPetId(),
                    order.getPetName(),
                    order.getAppointmentDate(),
                    order.getStartTime(),
                    order.getEndTime(),
                    money(order.getTotalAmount()),
                    money(order.getCouponDiscount()),
                    money(order.getEstimatedPayAmount()),
                    order.getCreatedAt()));
        }
        return views;
    }

    /**
     * 我的订单详情（含核销码、照片墙、券卡片与**本单的评价**）。
     *
     * <p>评价那一次查询（{@link OrderReviewService#findOfOrder}）只在详情里做，列表里没有：
     * 列表不需要回答「评过没有」（评价入口只在详情页），少一次按行查询。
     * 它是**用户自己写的**内容，所以放在订单详情里没有越权问题；服务者侧视图里没有它。
     */
    public OrderView mineView(ServiceOrder order) {
        return new OrderView(
                order.getId(),
                order.getOrderNo(),
                order.getStatus(),
                order.getProviderId(),
                providerFacts.providerNames(List.of(order.getProviderId())).get(order.getProviderId()),
                order.getServiceId(),
                order.getServiceName(),
                order.getPetId(),
                order.getPetName(),
                order.getAppointmentDate(),
                order.getStartTime(),
                order.getEndTime(),
                money(order.getTotalAmount()),
                money(order.getCouponDiscount()),
                money(order.getEstimatedPayAmount()),
                parties.coupon(order.getCouponId()).orElse(null),
                order.getRemark(),
                visibleRedeemCode(order),
                order.getAcceptedAt(),
                order.getRedeemedAt(),
                order.getReportedAt(),
                order.getReportRemark(),
                photoWall.viewOf(order.getId()),
                order.getCancelRequestStatus(),
                order.getCancelRequestedAt(),
                order.getCancelReason(),
                order.getCancelRejectedReason(),
                order.getCancelledAt(),
                order.getCancelledBy(),
                reviews.findOfOrder(order.getId()).orElse(null),
                order.getCreatedAt());
    }

    // ---------------------------------------------------------------- 服务者侧

    /** 本店订单列表（批量装配：门店名 / 预约人 / 宠物一次取回）。 */
    public List<ProviderOrderSummaryView> providerSummaries(List<ServiceOrder> orders) {
        Set<Long> userIds = new LinkedHashSet<>();
        Set<Long> petIds = new LinkedHashSet<>();
        Set<Long> couponIds = new LinkedHashSet<>();
        for (ServiceOrder order : orders) {
            userIds.add(order.getUserId());
            petIds.add(order.getPetId());
            if (order.getCouponId() != null) {
                couponIds.add(order.getCouponId());
            }
        }
        Map<Long, UserProfile> profiles = parties.profiles(userIds);
        Map<Long, Integer> species = parties.petSpecies(petIds);
        List<ProviderOrderSummaryView> views = new ArrayList<>(orders.size());
        for (ServiceOrder order : orders) {
            UserProfile profile = profiles.get(order.getUserId());
            views.add(new ProviderOrderSummaryView(
                    order.getId(),
                    order.getOrderNo(),
                    order.getStatus(),
                    order.getUserId(),
                    nicknameOf(profile),
                    OrderParties.phoneOf(profile),
                    order.getPetId(),
                    order.getPetName(),
                    species.getOrDefault(order.getPetId(), SPECIES_UNKNOWN),
                    order.getServiceId(),
                    order.getServiceName(),
                    order.getAppointmentDate(),
                    order.getStartTime(),
                    order.getEndTime(),
                    money(order.getTotalAmount()),
                    money(order.getCouponDiscount()),
                    money(order.getEstimatedPayAmount()),
                    order.getCouponId(),
                    order.getCancelRequestStatus(),
                    order.getCreatedAt()));
        }
        return views;
    }

    /** 本店订单详情（**不含核销码**：门店凭用户出示的码定位订单）。 */
    public ProviderOrderView providerView(ServiceOrder order) {
        UserProfile profile = parties.profiles(List.of(order.getUserId())).get(order.getUserId());
        Map<Long, Integer> species = parties.petSpecies(List.of(order.getPetId()));
        return new ProviderOrderView(
                order.getId(),
                order.getOrderNo(),
                order.getStatus(),
                order.getUserId(),
                nicknameOf(profile),
                OrderParties.phoneOf(profile),
                order.getPetId(),
                order.getPetName(),
                species.getOrDefault(order.getPetId(), SPECIES_UNKNOWN),
                order.getServiceId(),
                order.getServiceName(),
                order.getAppointmentDate(),
                order.getStartTime(),
                order.getEndTime(),
                money(order.getTotalAmount()),
                money(order.getCouponDiscount()),
                money(order.getEstimatedPayAmount()),
                parties.coupon(order.getCouponId()).orElse(null),
                order.getRemark(),
                order.getAcceptedAt(),
                order.getRedeemedAt(),
                order.getReportedAt(),
                order.getReportRemark(),
                photoWall.viewOf(order.getId()),
                order.getCancelRequestStatus(),
                order.getCancelRequestedAt(),
                order.getCancelReason(),
                order.getCancelRejectedReason(),
                order.getCancelledAt(),
                order.getCancelledBy(),
                order.getCreatedAt());
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 核销码对**用户**可见吗：状态在「已预约 / 履约中」**且**已到预约时间（ADR-0038 第二节）。
     *
     * <p>太早泄漏等于把凭证挂在外面；取消 / 完成之后也不再给（契约写死）。
     */
    private static String visibleRedeemCode(ServiceOrder order) {
        if (order.getStatus() == null || !OrderStatus.redeemCodeVisible(order.getStatus())) {
            return null;
        }
        if (order.getAppointmentDate() == null || order.getStartTime() == null) {
            return null;
        }
        LocalDateTime start = LocalDateTime.of(order.getAppointmentDate(), LocalTime.parse(order.getStartTime()));
        return AppTime.now().isBefore(start) ? null : order.getRedeemCode();
    }

    /** 预约人昵称：脱敏展示（docs/conventions.md 的姓名脱敏口径）。 */
    private static String nicknameOf(UserProfile profile) {
        return profile == null ? null : Masking.name(profile.nickname());
    }

    private static String money(BigDecimal amount) {
        return amount == null ? "0.00" : amount.toPlainString();
    }

    private static Collection<Long> providerIdsOf(List<ServiceOrder> orders) {
        Set<Long> ids = new LinkedHashSet<>();
        orders.forEach(order -> ids.add(order.getProviderId()));
        return ids;
    }
}
