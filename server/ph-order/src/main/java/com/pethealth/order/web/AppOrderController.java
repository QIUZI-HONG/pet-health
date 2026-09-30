package com.pethealth.order.web;

import com.pethealth.api.order.AppointmentSlotView;
import com.pethealth.api.order.OrderCancelRequest;
import com.pethealth.api.order.OrderCreateRequest;
import com.pethealth.api.order.OrderReviewRequest;
import com.pethealth.api.order.OrderReviewView;
import com.pethealth.api.order.OrderSummaryView;
import com.pethealth.api.order.OrderView;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.security.LoginDomain;
import com.pethealth.order.service.AppointmentSlotService;
import com.pethealth.order.service.OrderReviewService;
import com.pethealth.order.service.OrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * C 端的预约与订单接口，契约见 contract/app.yaml 的 {@code /providers/{provider_id}/appointment-slots}
 * 与 {@code /orders/**}（切片 #109；决策见 ADR-0038 第一节、ADR-0036）。
 *
 * <p>六个动作：看号源、下单（占用号源 + 锁定券）、我的订单、订单详情（含核销码与照片墙）、
 * 取消（按阶段：待接单直接取消，已预约转成取消申请）、**评价晒单**（完成后一单一评）。
 *
 * <p>另外两条接口**路径不在订单域，但数据在**，所以也挂在这里：
 * {@code GET /providers/{provider_id}/reviews}（某门店的评价列表）。
 * 取舍写在 {@code OrderReviewService} 的类注释里：路径按调用方语义、数据按归属。
 *
 * <p>两条与「钱」有关的界面义务写在这里，免得前端各处自己理解：**费用在门店直接付给服务者**，
 * 订单上的 {@code estimated_pay_amount} 只是展示用的预估（ADR-0036 / ADR-0038 第二节）。
 *
 * <p>别人的订单一律 40400（越权与不存在同码，docs/conventions.md）：用 id 探测别人的订单
 * 不该得到「存在但无权」这种有信息量的答复。
 */
@RestController
@RequestMapping("/api/v1/app")
@Validated
public class AppOrderController {

    private final OrderService orderService;
    private final AppointmentSlotService slotService;
    private final OrderReviewService orderReviewService;

    public AppOrderController(OrderService orderService, AppointmentSlotService slotService,
                              OrderReviewService orderReviewService) {
        this.orderService = orderService;
        this.slotService = slotService;
        this.orderReviewService = orderReviewService;
    }

    /**
     * 某服务项在某天的可预约时段（号源）。
     *
     * <p>约满的时段**照样返回**（{@code full=true}）：让它从列表里消失，用户会以为那天不营业。
     */
    @GetMapping("/providers/{provider_id}/appointment-slots")
    public ApiResponse<List<AppointmentSlotView>> appointmentSlots(
            @PathVariable("provider_id") long providerId,
            @RequestParam(name = "service_id") long serviceId,
            @RequestParam(name = "date") LocalDate date) {
        CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(slotService.list(providerId, serviceId, date));
    }

    /** 我的订单（分页 + 状态筛选）。**核销码不在列表里**（到店凭证只出现在详情，且到点才可见）。 */
    @GetMapping("/orders")
    public ApiResponse<PageResult<OrderSummaryView>> myOrders(
            @RequestParam(required = false)
            @Min(value = 0, message = "状态只能是 0–4")
            @Max(value = 4, message = "状态只能是 0–4") Integer status,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(name = "page_size", defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = PageResult.MAX_PAGE_SIZE, message = "每页最多 " + PageResult.MAX_PAGE_SIZE + " 条")
            long pageSize) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(orderService.listMine(userId, status, page, pageSize));
    }

    /** 下单：占用号源 + 锁定券（券不可用 80001 / 已核销 80002，时段被抢 40900）。 */
    @PostMapping("/orders")
    public ApiResponse<OrderView> create(@Valid @RequestBody OrderCreateRequest request) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(orderService.create(userId, request));
    }

    /** 订单详情（含核销码与三道照片墙的进度）。 */
    @GetMapping("/orders/{order_id}")
    public ApiResponse<OrderView> myOrder(@PathVariable("order_id") long orderId) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(orderService.getMine(userId, orderId));
    }

    /**
     * 取消订单：待接单直接取消；已预约只是**发起取消申请**（等门店同意，拒绝必填理由）。
     *
     * <p>重复取消、履约中或终态取消一律 40900（ADR-0038 第一节）。
     */
    @PostMapping("/orders/{order_id}/cancel")
    public ApiResponse<OrderView> cancel(@PathVariable("order_id") long orderId,
                                         @Valid @RequestBody(required = false) OrderCancelRequest request) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(orderService.cancelByUser(userId, orderId,
                request == null ? null : request.reason()));
    }

    /**
     * 评价晒单：评分必填（1–5）+ 一句话可选。
     *
     * <p>三条拒绝：不是我的订单 40400；订单不在「已完成」40900；一单只能评一次 40900
     * （口径**不是幂等**，见契约）。评价成功会带动门店评分重算与 REVIEW 行为分，
     * 但**响应形状只有评价本身**——那两件事不是这个调用方要看的。
     */
    @PostMapping("/orders/{order_id}/review")
    public ApiResponse<OrderReviewView> review(@PathVariable("order_id") long orderId,
                                               @Valid @RequestBody OrderReviewRequest request) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(orderReviewService.create(userId, orderId, request));
    }

    /**
     * 某门店的评价列表（分页，最近的在前）。
     *
     * <p><b>路径在门店域、数据归订单域</b>：这条读接口由订单模块服务，因为评价行是它的表
     * （理由与取舍写在 {@code OrderReviewService} 的类注释里）。
     *
     * <p>**要登录**（与知识库 / 社区同属「内容面」：游客只给「浏览服务」），但**不做门店可见性判定**
     * ——那套口径属于 ph-provider 的浏览面。所以这里不查「这家店可不可浏览」，
     * 不存在的门店返回 200 + 空列表。
     */
    @GetMapping("/providers/{provider_id}/reviews")
    public ApiResponse<PageResult<OrderReviewView>> providerReviews(
            @PathVariable("provider_id") long providerId,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(name = "page_size", defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = PageResult.MAX_PAGE_SIZE, message = "每页最多 " + PageResult.MAX_PAGE_SIZE + " 条")
            long pageSize) {
        return ApiResponse.ok(orderReviewService.listOfProvider(providerId, page, pageSize));
    }
}
