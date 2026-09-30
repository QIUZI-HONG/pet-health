package com.pethealth.provider.web;

import com.pethealth.api.app.CatalogItemProviderView;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.api.PageResult;
import com.pethealth.provider.service.ProviderBrowseService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「按项目找店」：做某个目录项的门店与它们的定价，契约见 contract/app.yaml 的
 * {@code /catalog/items/{item_code}/providers}。
 *
 * <p><b>路径前缀是 catalog，但数据归 ph-provider</b>：这一条查的是「谁在卖这个项目」——
 * 条件落在 {@code provider_service} 与门店可见性上（这两张表是服务者的）。按路径前缀分模块
 * 会逼着 ph-catalog 去 join 别人的表（ADR-0006 禁止）或为它单开一个只用一个方法的接口；
 * 按数据归属分模块则只需复用已有的 {@code CatalogQueryApi}（名称、单位、启用状态）。
 * 同一个前缀下分属两个模块是刻意的，与 {@code /app/providers/{id}/appointment-slots}
 * 落在 ph-order 是同一条取舍。
 *
 * <p>与 {@code /providers} 同属「只读、不需要身份」那一组（ADR-0037 第一节）：
 * 本类没有一处 {@code CurrentUser} 调用，免登录在 {@code JwtAuthenticationFilter} 的免登录表里放行。
 */
@RestController
@RequestMapping("/api/v1/app/catalog/items")
@Validated
public class AppCatalogItemController {

    private final ProviderBrowseService browseService;

    public AppCatalogItemController(ProviderBrowseService browseService) {
        this.browseService = browseService;
    }

    /** 做这个项目的门店（只列可下单且该项目在架的门店）。项目不存在或已停用回 40400。 */
    @GetMapping("/{item_code}/providers")
    public ApiResponse<PageResult<CatalogItemProviderView>> providers(
            @PathVariable("item_code")
            @Size(max = 32, message = "项目编码最长 32 个字符") String itemCode,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(name = "page_size", defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = PageResult.MAX_PAGE_SIZE, message = "每页最多 " + PageResult.MAX_PAGE_SIZE + " 条")
            long pageSize) {
        return ApiResponse.ok(browseService.providersOfItem(itemCode, page, pageSize));
    }
}
