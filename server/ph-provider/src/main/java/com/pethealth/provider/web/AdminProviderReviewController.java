package com.pethealth.provider.web;

import com.pethealth.api.admin.ProviderStatusRequest;
import com.pethealth.api.admin.ReviewApproveRequest;
import com.pethealth.api.admin.ReviewRejectRequest;
import com.pethealth.api.provider.OnboardingApplicationSummary;
import com.pethealth.api.provider.OnboardingApplicationView;
import com.pethealth.api.provider.ProviderProfileView;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.security.LoginDomain;
import com.pethealth.provider.domain.OnboardingApplication;
import com.pethealth.provider.service.OnboardingService;
import com.pethealth.provider.service.ProviderAdminService;
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

/**
 * 运营后台的服务者审核接口，契约见 contract/admin.yaml 的
 * {@code /provider-applications/**} 与 {@code /providers/**}。
 *
 * <p>两块内容合在一个控制器里，因为它们操作的是同一个对象（服务者）生命周期的两段：
 * **申请单**的审核（准入）与**经营状态**的处置（准入之后）。分成两个控制器会让两处都要注入
 * 同样的服务者查询，而它们的权限口径是同一个（登录域 admin）。
 *
 * <p>交付文档 2.2 的权限矩阵在这里落地成「登录域必须是 admin」；矩阵里更细的角色
 * （平台运营 vs 超级管理员）还没有可判的依据，见 ADR-0035 的「需要协调」。
 */
@RestController
@RequestMapping("/api/v1/admin")
@Validated
public class AdminProviderReviewController {

    private static final int KEYWORD_MAX_LENGTH = 64;

    private final OnboardingService onboardingService;
    private final ProviderAdminService providerAdminService;

    public AdminProviderReviewController(OnboardingService onboardingService,
                                         ProviderAdminService providerAdminService) {
        this.onboardingService = onboardingService;
        this.providerAdminService = providerAdminService;
    }

    /** 待办队列：不传 status 时**只给待审核**（队列的默认含义就是待办）。 */
    @GetMapping("/provider-applications")
    public ApiResponse<PageResult<OnboardingApplicationSummary>> applications(
            @RequestParam(required = false, defaultValue = "0")
            @Min(value = 0, message = "状态只能是 0（待审核）1（通过）2（驳回）")
            @Max(value = 2, message = "状态只能是 0（待审核）1（通过）2（驳回）") Integer status,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(name = "page_size", defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = PageResult.MAX_PAGE_SIZE, message = "每页最多 " + PageResult.MAX_PAGE_SIZE + " 条")
            long pageSize) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(onboardingService.listForReview(status, page, pageSize));
    }

    @GetMapping("/provider-applications/{application_id}")
    public ApiResponse<OnboardingApplicationView> application(@PathVariable("application_id") long applicationId) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(onboardingService.getForReview(applicationId));
    }

    /** 审核通过：服务者转为正常、资质转为通过、申请人绑定为该服务者的管理员。 */
    @PostMapping("/provider-applications/{application_id}/approve")
    public ApiResponse<OnboardingApplicationView> approve(@PathVariable("application_id") long applicationId,
                                                          @RequestBody(required = false)
                                                          ReviewApproveRequest request) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(onboardingService.approve(applicationId, request));
    }

    /** 审核驳回：原因必填，原样展示给服务者。 */
    @PostMapping("/provider-applications/{application_id}/reject")
    public ApiResponse<OnboardingApplicationView> reject(@PathVariable("application_id") long applicationId,
                                                         @Valid @RequestBody ReviewRejectRequest request) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(onboardingService.reject(applicationId, request));
    }

    /** 服务者列表。**关键字只搜名称**：电话是密文，模糊搜索在 ADR-0013 里被明确排除。 */
    @GetMapping("/providers")
    public ApiResponse<PageResult<ProviderProfileView>> providers(
            @RequestParam(required = false)
            @Min(value = 0, message = "状态只能是 0（待审核）1（正常）2（驳回）3（冻结）")
            @Max(value = 3, message = "状态只能是 0（待审核）1（正常）2（驳回）3（冻结）") Integer status,
            @RequestParam(required = false)
            @Min(value = 1, message = "服务者类型只能是 1–6")
            @Max(value = 6, message = "服务者类型只能是 1–6") Integer type,
            @RequestParam(required = false)
            @Size(max = KEYWORD_MAX_LENGTH, message = "关键字最长 " + KEYWORD_MAX_LENGTH + " 个字符") String keyword,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(name = "page_size", defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = PageResult.MAX_PAGE_SIZE, message = "每页最多 " + PageResult.MAX_PAGE_SIZE + " 条")
            long pageSize) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(providerAdminService.list(status, type, keyword, page, pageSize));
    }

    @GetMapping("/providers/{provider_id}")
    public ApiResponse<ProviderProfileView> provider(@PathVariable("provider_id") long providerId) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(providerAdminService.get(providerId));
    }

    /** 冻结（3）/ 解冻（1）——清退的落点；被驳回的服务者不能从这里恢复。 */
    @PutMapping("/providers/{provider_id}/status")
    public ApiResponse<ProviderProfileView> changeProviderStatus(@PathVariable("provider_id") long providerId,
                                                                @Valid @RequestBody ProviderStatusRequest request) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(providerAdminService.changeStatus(providerId, request));
    }

    /** 实体常量在这里引一次，防止有人把 0/1/2 写成魔法数字。 */
    static final int STATUS_PENDING = OnboardingApplication.STATUS_PENDING;
}
