package com.pethealth.provider.web;

import com.pethealth.api.catalog.CatalogItemProposalRequest;
import com.pethealth.api.catalog.CatalogItemProposalSummary;
import com.pethealth.api.catalog.CatalogItemProposalView;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.security.LoginDomain;
import com.pethealth.provider.service.CatalogProposalService;
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
 * 服务者后台的「目录外服务提案」接口，契约见 contract/provider.yaml 的
 * {@code /catalog/item-requests}。
 *
 * <p>目录是平台的（F010），服务者只能提案：通过之后平台上多了一个正式项目，
 * 他才能在自己店里勾选并定价（交付文档 F012 / 2.5）。
 *
 * <p>提案是**门店级**动作，所以要求服务者已通过入驻审核；但不必等资质未过期——
 * 资质过期只影响上架，不影响提建议。
 */
@RestController
@RequestMapping("/api/v1/provider/catalog/item-requests")
@Validated
public class ProviderCatalogProposalController {

    private final CatalogProposalService proposalService;

    public ProviderCatalogProposalController(CatalogProposalService proposalService) {
        this.proposalService = proposalService;
    }

    @GetMapping
    public ApiResponse<PageResult<CatalogItemProposalSummary>> list(
            @RequestParam(required = false)
            @Min(value = 0, message = "状态只能是 0（待审核）1（通过）2（驳回）")
            @Max(value = 2, message = "状态只能是 0（待审核）1（通过）2（驳回）") Integer status,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(name = "page_size", defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = PageResult.MAX_PAGE_SIZE, message = "每页最多 " + PageResult.MAX_PAGE_SIZE + " 条")
            long pageSize) {
        CurrentUser.requireDomain(LoginDomain.PROVIDER);
        return ApiResponse.ok(proposalService.listMine(status, page, pageSize));
    }

    @PostMapping
    public ApiResponse<CatalogItemProposalView> submit(@Valid @RequestBody CatalogItemProposalRequest request) {
        CurrentUser.requireDomain(LoginDomain.PROVIDER);
        return ApiResponse.ok(proposalService.submit(request));
    }

    @GetMapping("/{request_id}")
    public ApiResponse<CatalogItemProposalView> get(@PathVariable("request_id") long requestId) {
        CurrentUser.requireDomain(LoginDomain.PROVIDER);
        return ApiResponse.ok(proposalService.getMine(requestId));
    }
}
