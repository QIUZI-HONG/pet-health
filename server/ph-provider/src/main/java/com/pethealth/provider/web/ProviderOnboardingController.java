package com.pethealth.provider.web;

import com.pethealth.api.provider.OnboardingApplicationRequest;
import com.pethealth.api.provider.OnboardingApplicationSummary;
import com.pethealth.api.provider.OnboardingApplicationView;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.security.LoginDomain;
import com.pethealth.provider.domain.OnboardingApplication;
import com.pethealth.provider.service.OnboardingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
 * 服务者后台的入驻申请接口，契约见 contract/provider.yaml 的 {@code /onboarding/**}。
 *
 * <p>**注意这里没有运营的审核动作**：审核在 {@code /api/v1/admin/provider-applications/**}
 * （另一个登录域）。申请与审核分属两个端，接口也分开——把审核挂在同一个前缀下，
 * 就得靠角色判断来兜底，而角色判断恰恰是最容易漏的一层（ADR-0012 用登录域先切一刀）。
 */
@RestController
@RequestMapping("/api/v1/provider/onboarding/applications")
@Validated
public class ProviderOnboardingController {

    private final OnboardingService onboardingService;

    public ProviderOnboardingController(OnboardingService onboardingService) {
        this.onboardingService = onboardingService;
    }

    /** 我提交过的申请（进度与驳回原因）。 */
    @GetMapping
    public ApiResponse<PageResult<OnboardingApplicationSummary>> list(
            @RequestParam(required = false)
            @Min(value = 0, message = "状态只能是 0（待审核）1（通过）2（驳回）")
            @Max(value = 2, message = "状态只能是 0（待审核）1（通过）2（驳回）") Integer status,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(name = "page_size", defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = PageResult.MAX_PAGE_SIZE, message = "每页最多 " + PageResult.MAX_PAGE_SIZE + " 条")
            long pageSize) {
        long userId = CurrentUser.requireDomain(LoginDomain.PROVIDER);
        return ApiResponse.ok(onboardingService.listMine(status, page, pageSize));
    }

    /** 提交入驻申请（含资质材料）。同一账号最多一份待审核申请。 */
    @PostMapping
    public ApiResponse<OnboardingApplicationView> submit(@Valid @RequestBody OnboardingApplicationRequest request) {
        CurrentUser.requireDomain(LoginDomain.PROVIDER);
        return ApiResponse.ok(onboardingService.submit(request));
    }

    /** 申请详情（含材料与审核流水）。别人的申请返回 40400。 */
    @GetMapping("/{application_id}")
    public ApiResponse<OnboardingApplicationView> get(@PathVariable("application_id") long applicationId) {
        CurrentUser.requireDomain(LoginDomain.PROVIDER);
        return ApiResponse.ok(onboardingService.getMine(applicationId));
    }

    /** 驳回后修改重提：**同一份申请单**回到待审，不新建服务者记录。 */
    @PutMapping("/{application_id}")
    public ApiResponse<OnboardingApplicationView> resubmit(
            @PathVariable("application_id") long applicationId,
            @Valid @RequestBody OnboardingApplicationRequest request) {
        CurrentUser.requireDomain(LoginDomain.PROVIDER);
        return ApiResponse.ok(onboardingService.resubmit(applicationId, request));
    }

    /** 状态常量在这里用一次，避免有人把「0/1/2」当成魔法数字写进 controller 的新方法里。 */
    static final int STATUS_PENDING = OnboardingApplication.STATUS_PENDING;
}
