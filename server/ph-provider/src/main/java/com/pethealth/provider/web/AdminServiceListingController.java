package com.pethealth.provider.web;

import com.pethealth.api.admin.ReviewApproveRequest;
import com.pethealth.api.admin.ReviewRejectRequest;
import com.pethealth.api.provider.ProviderServiceView;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.security.LoginDomain;
import com.pethealth.provider.domain.ProviderServiceListing;
import com.pethealth.provider.service.ServiceListingService;
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

/**
 * 运营后台的服务上架审核，契约见 contract/admin.yaml 的 {@code /service-listings/**}。
 *
 * <p>这是交付文档 2.2 里「审核商家/服务」（文档用词）那一格的后半截：服务者定价在区间内只是必要条件，
 * 上架还要平台看一眼。**通过即上架**——服务者提交审核的意图就是上架。
 *
 * <p>审核员看到的每一行都带着目录侧的名称与区间（现取，不是快照），
 * 所以「这个价是不是在区间内」不需要打开另一个页面去对。
 */
@RestController
@RequestMapping("/api/v1/admin/service-listings")
@Validated
public class AdminServiceListingController {

    private final ServiceListingService listingService;

    public AdminServiceListingController(ServiceListingService listingService) {
        this.listingService = listingService;
    }

    /** 不传 status 时只给待审核（队列的默认含义就是待办）。 */
    @GetMapping
    public ApiResponse<PageResult<ProviderServiceView>> listings(
            @RequestParam(required = false, defaultValue = "0")
            @Min(value = 0, message = "状态只能是 0 待审核 / 1 已上架 / 2 已下架 / 3 已驳回")
            @Max(value = 3, message = "状态只能是 0 待审核 / 1 已上架 / 2 已下架 / 3 已驳回") Integer status,
            @RequestParam(name = "provider_id", required = false) Long providerId,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(name = "page_size", defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = PageResult.MAX_PAGE_SIZE, message = "每页最多 " + PageResult.MAX_PAGE_SIZE + " 条")
            long pageSize) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(listingService.listForReview(status, providerId, page, pageSize));
    }

    @PostMapping("/{service_id}/approve")
    public ApiResponse<ProviderServiceView> approve(@PathVariable("service_id") long serviceId,
                                                    @RequestBody(required = false) ReviewApproveRequest request) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(listingService.approve(serviceId, request));
    }

    @PostMapping("/{service_id}/reject")
    public ApiResponse<ProviderServiceView> reject(@PathVariable("service_id") long serviceId,
                                                   @Valid @RequestBody ReviewRejectRequest request) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(listingService.reject(serviceId, request));
    }

    /** 状态常量在这里引一次，避免魔法数字。 */
    static final int STATUS_PENDING = ProviderServiceListing.STATUS_PENDING;
}
