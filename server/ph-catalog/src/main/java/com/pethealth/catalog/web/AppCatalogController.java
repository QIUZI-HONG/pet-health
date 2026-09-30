package com.pethealth.catalog.web;

import com.pethealth.api.app.CatalogCategoryView;
import com.pethealth.api.app.CatalogItemView;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.api.PageResult;
import com.pethealth.catalog.service.CatalogQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 标准服务目录的 C 端浏览（分类导航 + 目录项），契约见 contract/app.yaml 的 {@code /catalog/**}。
 *
 * <p>与 {@code /providers} 同属「只读、不需要身份」那一组（ADR-0037 第一节），所以本类里没有一处
 * {@code CurrentUser} 调用；免登录是在 {@code JwtAuthenticationFilter} 的免登录表里放行的。
 *
 * <p><b>为什么这两条要有</b>：分类名与项目列表都是运营维护在库里的数据，而它们同时被三处用到
 * （服务页的分类筛选、按项目浏览、运营后台的目录维护）。前端各抄一份常量的话，运营改了名字之后
 * C 端会一直显示旧名字——这一组接口让「名字只有一个来源」这条口径在 C 端也成立。
 *
 * <p>「这个项目哪家店能做」不在这里，在 ph-provider（`/catalog/items/{code}/providers`）：
 * 那一半查的是服务者的在架服务项与门店可见性，数据归它。同一个前缀下分属两个模块是刻意的，
 * 与 {@code /app/providers/{id}/appointment-slots} 落在 ph-order 同理——**按数据归属分模块，不按路径前缀**。
 */
@RestController
@RequestMapping("/api/v1/app/catalog")
@Validated
public class AppCatalogController {

    private final CatalogQueryService queryService;

    public AppCatalogController(CatalogQueryService queryService) {
        this.queryService = queryService;
    }

    /** 分类导航：只给启用中的分类，按运营维护的顺序。 */
    @GetMapping("/categories")
    public ApiResponse<List<CatalogCategoryView>> categories() {
        return ApiResponse.ok(queryService.appCategories());
    }

    /** 目录项：项目 + 平台区间价（区间不是某家店的报价，界面上要说清楚）。 */
    @GetMapping("/items")
    public ApiResponse<PageResult<CatalogItemView>> items(
            @RequestParam(name = "category_code", required = false)
            @Size(max = 32, message = "分类编码最长 32 个字符") String categoryCode,
            @RequestParam(required = false)
            @Size(max = 32, message = "关键词最长 32 个字符") String keyword,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(name = "page_size", defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = PageResult.MAX_PAGE_SIZE, message = "每页最多 " + PageResult.MAX_PAGE_SIZE + " 条")
            long pageSize) {
        return ApiResponse.ok(queryService.appItems(categoryCode, keyword, page, pageSize));
    }
}
