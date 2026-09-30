package com.pethealth.privilege.web;

import com.pethealth.api.privilege.CouponDtos;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.security.LoginDomain;
import com.pethealth.privilege.service.CouponContributionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务者后台的券池接口，契约见 contract/provider.yaml 的 {@code /coupon-pool/**} 与
 * {@code /coupon-contributions/**}（切片 #110，决策见 ADR-0037 第三节 / ADR-0044）。
 *
 * <p>四个动作分开成四个接口，理由与选品定价那一组相同：**每条路径只有一个意思**。
 * 「撤回」是 {@code DELETE}（把未发放的额度收回），不是「把 status 改成 2 的 PUT」——
 * 后者会让人以为它可以和调额度一起做，而两者的约束不同（撤回把额度夹到下限，调额不许低于下限）。
 *
 * <p>**没有核销接口**：券的核销是订单侧的动作（ADR-0038 第二节），本文件只给统计与明细。
 * 服务者想核销要去订单里核销，不是在这里点一下券就作废了。
 */
@RestController
@RequestMapping("/api/v1/provider")
@Validated
public class ProviderCouponController {

    private final CouponContributionService contributionService;

    public ProviderCouponController(CouponContributionService contributionService) {
        this.contributionService = contributionService;
    }

    /** 券池里可贡献的模板（只有服务者成本的券——平台补贴券不让服务者承诺额度）。 */
    @GetMapping("/coupon-pool/templates")
    public ApiResponse<PageResult<CouponDtos.CouponTemplateView>> templates(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(name = "page_size", defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = PageResult.MAX_PAGE_SIZE, message = "每页最多 " + PageResult.MAX_PAGE_SIZE + " 条")
            long pageSize) {
        CurrentUser.requireDomain(LoginDomain.PROVIDER);
        return ApiResponse.ok(contributionService.listContributableTemplates(keyword, page, pageSize));
    }

    /** 我的券贡献（含额度账：承诺 / 已发放 / 已核销 / 占用中 / 已过期 / 可发放）。 */
    @GetMapping("/coupon-contributions")
    public ApiResponse<PageResult<CouponDtos.CouponContributionView>> contributions(
            @RequestParam(required = false)
            @Min(value = 1, message = "状态只能是 1 生效中 / 2 已停止发放")
            @Max(value = 2, message = "状态只能是 1 生效中 / 2 已停止发放") Integer status,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(name = "page_size", defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = PageResult.MAX_PAGE_SIZE, message = "每页最多 " + PageResult.MAX_PAGE_SIZE + " 条")
            long pageSize) {
        CurrentUser.requireDomain(LoginDomain.PROVIDER);
        return ApiResponse.ok(contributionService.listMine(status, page, pageSize));
    }

    /** 从券池选券并承诺可核销额度（**是承诺不是发放**）。 */
    @PostMapping("/coupon-contributions")
    public ApiResponse<CouponDtos.CouponContributionView> commit(
            @Valid @RequestBody CouponDtos.CouponContributionRequest request) {
        CurrentUser.requireDomain(LoginDomain.PROVIDER);
        return ApiResponse.ok(contributionService.commit(request));
    }

    /** 券贡献详情（含额度流水）。 */
    @GetMapping("/coupon-contributions/{contribution_id}")
    public ApiResponse<CouponDtos.CouponContributionView> detail(
            @PathVariable("contribution_id") long contributionId) {
        CurrentUser.requireDomain(LoginDomain.PROVIDER);
        return ApiResponse.ok(contributionService.detail(contributionId));
    }

    /** 调整额度（不得低于已核销加占用中）；传 status=2 等于撤回，传 1 重新启用。 */
    @PutMapping("/coupon-contributions/{contribution_id}")
    public ApiResponse<CouponDtos.CouponContributionView> update(
            @PathVariable("contribution_id") long contributionId,
            @Valid @RequestBody CouponDtos.CouponContributionRequest request) {
        CurrentUser.requireDomain(LoginDomain.PROVIDER);
        return ApiResponse.ok(contributionService.update(contributionId, request));
    }

    /** 撤回未发放的额度（已发出的券照常有效）。 */
    @DeleteMapping("/coupon-contributions/{contribution_id}")
    public ApiResponse<CouponDtos.CouponContributionView> withdraw(
            @PathVariable("contribution_id") long contributionId) {
        CurrentUser.requireDomain(LoginDomain.PROVIDER);
        return ApiResponse.ok(contributionService.withdraw(contributionId));
    }

    /** 本店贡献券的发放与核销明细（服务者的对账视图：不带领券人身份）。 */
    @GetMapping("/coupon-contributions/{contribution_id}/coupons")
    public ApiResponse<PageResult<CouponDtos.CouponView>> coupons(
            @PathVariable("contribution_id") long contributionId,
            @RequestParam(required = false)
            @Min(value = 1, message = "状态只能是 1 待使用 / 2 已锁定 / 3 已核销 / 4 已过期")
            @Max(value = 4, message = "状态只能是 1 待使用 / 2 已锁定 / 3 已核销 / 4 已过期") Integer status,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(name = "page_size", defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = PageResult.MAX_PAGE_SIZE, message = "每页最多 " + PageResult.MAX_PAGE_SIZE + " 条")
            long pageSize) {
        CurrentUser.requireDomain(LoginDomain.PROVIDER);
        return ApiResponse.ok(contributionService.listCoupons(contributionId, status, page, pageSize));
    }
}
