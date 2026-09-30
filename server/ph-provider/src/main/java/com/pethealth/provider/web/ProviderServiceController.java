package com.pethealth.provider.web;

import com.pethealth.api.provider.ProviderServiceRequest;
import com.pethealth.api.provider.ProviderServiceStatusRequest;
import com.pethealth.api.provider.ProviderServiceView;
import com.pethealth.api.provider.ServicePriceRequest;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.security.LoginDomain;
import com.pethealth.provider.service.ServiceListingService;
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
 * 服务者后台的选品定价与上架接口，契约见 contract/provider.yaml 的 {@code /services}。
 *
 * <p>三个动作分开成三个接口，而不是一个「保存」包办：
 *
 * <ul>
 *   <li>{@code POST /services} 勾选目录项并定价（进待审核）；
 *   <li>{@code PUT /services/{id}} 改价（同样进待审核，已上架的会先撤下）；
 *   <li>{@code PUT /services/{id}/status} 上架 / 下架（审核通过后才是合法动作）。
 * </ul>
 *
 * <p>合成一个接口会让「上架」与「改价」共用一条路径，于是「改价顺手把商品下架了」
 * 这类副作用只能靠参数区分——分开之后每条路径只有一个意思。
 */
@RestController
@RequestMapping("/api/v1/provider/services")
@Validated
public class ProviderServiceController {

    private final ServiceListingService listingService;

    public ProviderServiceController(ServiceListingService listingService) {
        this.listingService = listingService;
    }

    @GetMapping
    public ApiResponse<PageResult<ProviderServiceView>> list(
            @RequestParam(required = false)
            @Min(value = 0, message = "状态只能是 0 待审核 / 1 已上架 / 2 已下架 / 3 已驳回")
            @Max(value = 3, message = "状态只能是 0 待审核 / 1 已上架 / 2 已下架 / 3 已驳回") Integer status,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(name = "page_size", defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = PageResult.MAX_PAGE_SIZE, message = "每页最多 " + PageResult.MAX_PAGE_SIZE + " 条")
            long pageSize) {
        CurrentUser.requireDomain(LoginDomain.PROVIDER);
        return ApiResponse.ok(listingService.listMine(status, page, pageSize));
    }

    /** 勾选目录项并定价。价格越界回 90001（HTTP 200），message 里带着区间。 */
    @PostMapping
    public ApiResponse<ProviderServiceView> create(@Valid @RequestBody ProviderServiceRequest request) {
        CurrentUser.requireDomain(LoginDomain.PROVIDER);
        return ApiResponse.ok(listingService.create(request));
    }

    /** 改价：回到待审核。 */
    @PutMapping("/{service_id}")
    public ApiResponse<ProviderServiceView> updatePrice(@PathVariable("service_id") long serviceId,
                                                        @Valid @RequestBody ServicePriceRequest request) {
        CurrentUser.requireDomain(LoginDomain.PROVIDER);
        return ApiResponse.ok(listingService.updatePrice(serviceId, request));
    }

    /** 上架（1）/ 下架（2）。未审核通过就上架 → 40300，且消息里说明当前处于哪个状态。 */
    @PutMapping("/{service_id}/status")
    public ApiResponse<ProviderServiceView> changeStatus(@PathVariable("service_id") long serviceId,
                                                         @Valid @RequestBody ProviderServiceStatusRequest request) {
        CurrentUser.requireDomain(LoginDomain.PROVIDER);
        return ApiResponse.ok(listingService.changeStatus(serviceId, request));
    }
}
