package com.pethealth.privilege.web;

import com.pethealth.api.privilege.CouponDtos;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.security.LoginDomain;
import com.pethealth.privilege.service.CouponPoolService;
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
 * 运营后台的券池接口，契约见 contract/admin.yaml 的 {@code /coupon-templates/**}、
 * {@code /coupon-pool/overview} 与 {@code /coupons}（切片 #110，决策见 ADR-0037 / ADR-0044）。
 *
 * <p>运营能做的三件事：维护券模板（**服务者不能自建券**，这是「统一券池」的核心）、
 * 看券池总览与对账、定向发放平台补贴券。
 *
 * <p>**没有「发服务者券」的入口**：服务者成本的券只从服务者的贡献额度里出，
 * 平台替服务者放券等于替它承诺额度——那是服务者自己的经营决定。
 */
@RestController
@RequestMapping("/api/v1/admin")
@Validated
public class AdminCouponController {

    private final CouponPoolService poolService;

    public AdminCouponController(CouponPoolService poolService) {
        this.poolService = poolService;
    }

    /** 券模板分页（含停用）。 */
    @GetMapping("/coupon-templates")
    public ApiResponse<PageResult<CouponDtos.CouponTemplateView>> templates(
            @RequestParam(required = false)
            @Min(value = 0, message = "状态只能是 1 启用 / 0 停用")
            @Max(value = 1, message = "状态只能是 1 启用 / 0 停用") Integer status,
            @RequestParam(name = "cost_bearer", required = false)
            @Min(value = 1, message = "成本归属只能是 1 服务者成本 / 2 平台补贴")
            @Max(value = 2, message = "成本归属只能是 1 服务者成本 / 2 平台补贴") Integer costBearer,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(name = "page_size", defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = PageResult.MAX_PAGE_SIZE, message = "每页最多 " + PageResult.MAX_PAGE_SIZE + " 条")
            long pageSize) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(poolService.listTemplates(status, costBearer, keyword, page, pageSize));
    }

    /** 新建券模板（编码不可复用；适用范围必须在标准目录里存在）。 */
    @PostMapping("/coupon-templates")
    public ApiResponse<CouponDtos.CouponTemplateView> createTemplate(
            @Valid @RequestBody CouponDtos.CouponTemplateRequest request) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(poolService.createTemplate(request));
    }

    /** 修改券模板（编码与成本归属不可改；改动只影响之后新发的券）。 */
    @PutMapping("/coupon-templates/{template_id}")
    public ApiResponse<CouponDtos.CouponTemplateView> updateTemplate(
            @PathVariable("template_id") long templateId,
            @Valid @RequestBody CouponDtos.CouponTemplateRequest request) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(poolService.updateTemplate(templateId, request));
    }

    /** 上架 / 下架（停用只挡新的发放与新的贡献，已发出的券照常可核销）。 */
    @PutMapping("/coupon-templates/{template_id}/status")
    public ApiResponse<CouponDtos.CouponTemplateView> changeTemplateStatus(
            @PathVariable("template_id") long templateId,
            @Valid @RequestBody CouponDtos.CouponTemplateStatusRequest request) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(poolService.changeTemplateStatus(templateId, request));
    }

    /** 券池总览（含对账：实例数 = 已发放 = 已核销 + 未过期未核销 + 已过期未核销）。 */
    @GetMapping("/coupon-pool/overview")
    public ApiResponse<CouponDtos.CouponPoolOverviewView> overview() {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(poolService.overview());
    }

    /** 券实例分页（运营查询与排查）。 */
    @GetMapping("/coupons")
    public ApiResponse<PageResult<CouponDtos.CouponView>> coupons(
            @RequestParam(name = "template_id", required = false) Long templateId,
            @RequestParam(required = false)
            @Min(value = 1, message = "来源只能是 1–5")
            @Max(value = 5, message = "来源只能是 1–5") Integer source,
            @RequestParam(required = false)
            @Min(value = 1, message = "状态只能是 1 待使用 / 2 已锁定 / 3 已核销 / 4 已过期")
            @Max(value = 4, message = "状态只能是 1 待使用 / 2 已锁定 / 3 已核销 / 4 已过期") Integer status,
            @RequestParam(name = "user_id", required = false) Long userId,
            @RequestParam(name = "provider_id", required = false) Long providerId,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(name = "page_size", defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = PageResult.MAX_PAGE_SIZE, message = "每页最多 " + PageResult.MAX_PAGE_SIZE + " 条")
            long pageSize) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(poolService.listCoupons(templateId, source, status, userId, providerId,
                page, pageSize));
    }

    /** 定向发放平台补贴券（**只发平台补贴券**：服务者成本的券由服务者的贡献额度决定）。 */
    @PostMapping("/coupons")
    public ApiResponse<CouponDtos.CouponView> issueCoupon(
            @Valid @RequestBody CouponDtos.IssueCouponRequest request) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(poolService.issueSubsidyCoupon(request.userId(), request.templateId(),
                request.remark()));
    }
}
