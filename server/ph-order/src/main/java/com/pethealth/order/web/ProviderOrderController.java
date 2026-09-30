package com.pethealth.order.web;

import com.pethealth.api.order.OrderCancelRejectRequest;
import com.pethealth.api.order.ProviderOrderCancelRequest;
import com.pethealth.api.order.OrderPhotoSlotRequest;
import com.pethealth.api.order.OrderPhotoWallView;
import com.pethealth.api.order.OrderReportRequest;
import com.pethealth.api.order.ProviderOrderSummaryView;
import com.pethealth.api.order.ProviderOrderView;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.security.LoginDomain;
import com.pethealth.order.service.OrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 服务者后台的订单接口，契约见 contract/provider.yaml 的 {@code /orders/**}（切片 #109）。
 *
 * <p>门店的完整履约链路：接单 → 核销 → 拍三道照片墙 → 报工，外加取消（含同意 / 拒绝用户的
 * 取消申请）。每一步的权限都是**服务者管理员与技师**（ADR-0037 第一节的矩阵），
 * 所以这里只校验登录域，不细分角色——技师本来就要能核销与报工。
 *
 * <p>三条服务者侧的界面义务：
 *
 * <ul>
 *   <li>**核销码不下发**（列表与详情都没有它）：门店凭用户出示的码定位订单，
 *       不需要知道码本身（ADR-0038 第二节）；
 *   <li>**核销 ≠ 收款**：核销只是履约确认，界面必须写明「费用在门店直接付给服务者」（ADR-0036）；
 *   <li>**报工缺照片要说清缺哪一道**：接口在 40900 的 message 里点名，
 *       前端从订单详情的 {@code photo_wall.missing_slots} 取同一份结论。
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/provider/orders")
@Validated
public class ProviderOrderController {

    private final OrderService orderService;

    public ProviderOrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * 本店订单列表（分页 + 状态 / 日期筛选 + **核销定位**）。
     *
     * <p>{@code keyword} 是核销的三个入口：订单号按包含匹配（用户常常只报后几位）、
     * 完整手机号与 6 位核销码按精确匹配（ADR-0038 第二节）。门店**不需要扫码枪之类的硬件**。
     */
    @GetMapping
    public ApiResponse<PageResult<ProviderOrderSummaryView>> list(
            @RequestParam(required = false)
            @Min(value = 0, message = "状态只能是 0–4")
            @Max(value = 4, message = "状态只能是 0–4") Integer status,
            @RequestParam(name = "appointment_date", required = false) LocalDate appointmentDate,
            @RequestParam(required = false) @Size(max = 32, message = "搜索关键字最长 32 个字符") String keyword,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(name = "page_size", defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = PageResult.MAX_PAGE_SIZE, message = "每页最多 " + PageResult.MAX_PAGE_SIZE + " 条")
            long pageSize) {
        long providerUserId = CurrentUser.requireDomain(LoginDomain.PROVIDER);
        return ApiResponse.ok(orderService.listForProvider(providerUserId, status, appointmentDate,
                keyword, page, pageSize));
    }

    /** 订单详情（含照片墙与取消申请，**不含核销码**）。 */
    @GetMapping("/{order_id}")
    public ApiResponse<ProviderOrderView> detail(@PathVariable("order_id") long orderId) {
        long providerUserId = CurrentUser.requireDomain(LoginDomain.PROVIDER);
        return ApiResponse.ok(orderService.getForProvider(providerUserId, orderId));
    }

    /** 接单（待接单 → 已预约）。重复接单或状态不对 → 40900。 */
    @PostMapping("/{order_id}/accept")
    public ApiResponse<ProviderOrderView> accept(@PathVariable("order_id") long orderId) {
        long providerUserId = CurrentUser.requireDomain(LoginDomain.PROVIDER);
        return ApiResponse.ok(orderService.accept(providerUserId, orderId));
    }

    /**
     * 核销（已预约 → 履约中）：**原子的条件更新**，并发双击只有一个成功，
     * 重复核销 40900（不是幂等成功）；券的问题按券的码回（80001 / 80002）。
     */
    @PostMapping("/{order_id}/redeem")
    public ApiResponse<ProviderOrderView> redeem(@PathVariable("order_id") long orderId) {
        long providerUserId = CurrentUser.requireDomain(LoginDomain.PROVIDER);
        return ApiResponse.ok(orderService.redeem(providerUserId, orderId));
    }

    /**
     * 门店取消订单（含无法履约）：待接单 / 已预约可取消，履约中与终态 40900。
     *
     * <p>**理由必填**（ADR-0049 §七）：门店单方取消对用户是实打实的伤害，理由进审计与考核过程分。
     * 入参是 {@link ProviderOrderCancelRequest}（与用户侧那份是两个对象，见它的类注释）。
     */
    @PostMapping("/{order_id}/cancel")
    public ApiResponse<ProviderOrderView> cancel(@PathVariable("order_id") long orderId,
                                                 @Valid @RequestBody ProviderOrderCancelRequest request) {
        long providerUserId = CurrentUser.requireDomain(LoginDomain.PROVIDER);
        return ApiResponse.ok(orderService.cancelByProvider(providerUserId, orderId, request.reason()));
    }

    /** 同意用户的取消申请：订单 → 已取消，号源与券释放。没有待处理的申请 → 40900。 */
    @PostMapping("/{order_id}/cancel/approve")
    public ApiResponse<ProviderOrderView> approveCancel(@PathVariable("order_id") long orderId) {
        long providerUserId = CurrentUser.requireDomain(LoginDomain.PROVIDER);
        return ApiResponse.ok(orderService.approveCancel(providerUserId, orderId));
    }

    /** 拒绝用户的取消申请：**理由必填**（ADR-0038 第一节点名要求），拒绝后订单仍是已预约。 */
    @PostMapping("/{order_id}/cancel/reject")
    public ApiResponse<ProviderOrderView> rejectCancel(@PathVariable("order_id") long orderId,
                                                       @Valid @RequestBody OrderCancelRejectRequest request) {
        long providerUserId = CurrentUser.requireDomain(LoginDomain.PROVIDER);
        return ApiResponse.ok(orderService.rejectCancel(providerUserId, orderId, request.reason()));
    }

    /**
     * 保存一个照片槽位（**整体替换**：{@code file_ids} 是这一槽位的全量集合，传空数组即清空）。
     *
     * <p>只在「履约中」可写；照片必须是本单宠物的 {@code biz_type=care} 图，且没挂在别的订单上。
     */
    @PutMapping("/{order_id}/photo-slots/{slot}")
    public ApiResponse<OrderPhotoWallView> savePhotoSlot(@PathVariable("order_id") long orderId,
                                                         @PathVariable("slot") int slot,
                                                         @Valid @RequestBody OrderPhotoSlotRequest request) {
        long providerUserId = CurrentUser.requireDomain(LoginDomain.PROVIDER);
        return ApiResponse.ok(orderService.savePhotoSlot(providerUserId, orderId, slot, request));
    }

    /** 报工（履约中 → 已完成）：**三道照片墙各至少一张才允许**，缺一道会在 message 里点名。 */
    @PostMapping("/{order_id}/report")
    public ApiResponse<ProviderOrderView> report(@PathVariable("order_id") long orderId,
                                                 @Valid @RequestBody OrderReportRequest request) {
        long providerUserId = CurrentUser.requireDomain(LoginDomain.PROVIDER);
        return ApiResponse.ok(orderService.report(providerUserId, orderId, request.remark()));
    }
}
