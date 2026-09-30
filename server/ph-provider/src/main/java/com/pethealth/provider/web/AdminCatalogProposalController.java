package com.pethealth.provider.web;

import com.pethealth.api.admin.CatalogItemProposalApproveRequest;
import com.pethealth.api.admin.ReviewRejectRequest;
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
 * 运营后台的「目录外服务提案」审核，契约见 contract/admin.yaml 的
 * {@code /catalog/item-requests/**}。
 *
 * <p>通过时的**最终区间必填**：区间是平台规则（F010），默认采纳服务者的建议等于把规则制定权
 * 交给被审对象。编码可以指定也可以留空（留空按分类前缀排下一个序号）。
 *
 * <p>通过之后平台上多了一个正式目录项（由 ph-catalog 建，编码回写到提案单），
 * 服务者随后就能在自己店里勾选它并定价上架。
 */
@RestController
@RequestMapping("/api/v1/admin/catalog/item-requests")
@Validated
public class AdminCatalogProposalController {

    private final CatalogProposalService proposalService;

    public AdminCatalogProposalController(CatalogProposalService proposalService) {
        this.proposalService = proposalService;
    }

    @GetMapping
    public ApiResponse<PageResult<CatalogItemProposalSummary>> proposals(
            @RequestParam(required = false, defaultValue = "0")
            @Min(value = 0, message = "状态只能是 0（待审核）1（通过）2（驳回）")
            @Max(value = 2, message = "状态只能是 0（待审核）1（通过）2（驳回）") Integer status,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(name = "page_size", defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = PageResult.MAX_PAGE_SIZE, message = "每页最多 " + PageResult.MAX_PAGE_SIZE + " 条")
            long pageSize) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(proposalService.listForReview(status, page, pageSize));
    }

    @GetMapping("/{request_id}")
    public ApiResponse<CatalogItemProposalView> proposal(@PathVariable("request_id") long requestId) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(proposalService.getForReview(requestId));
    }

    @PostMapping("/{request_id}/approve")
    public ApiResponse<CatalogItemProposalView> approve(@PathVariable("request_id") long requestId,
                                                        @Valid @RequestBody
                                                        CatalogItemProposalApproveRequest request) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(proposalService.approve(requestId, request));
    }

    @PostMapping("/{request_id}/reject")
    public ApiResponse<CatalogItemProposalView> reject(@PathVariable("request_id") long requestId,
                                                       @Valid @RequestBody ReviewRejectRequest request) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(proposalService.reject(requestId, request));
    }
}
