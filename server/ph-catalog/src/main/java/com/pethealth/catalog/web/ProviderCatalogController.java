package com.pethealth.catalog.web;

import com.pethealth.api.catalog.ServiceCategoryView;
import com.pethealth.api.catalog.ServiceItemView;
import com.pethealth.catalog.domain.ServiceItem;
import com.pethealth.catalog.service.CatalogQueryService;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.security.LoginDomain;
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
 * 服务者后台读标准目录，契约见 contract/provider.yaml 的 {@code /catalog/**}。
 *
 * <p>与运营侧的区别只有两点，都在代码里固定下来、不由查询参数决定：
 *
 * <ul>
 *   <li><b>只看启用的</b>：停用的分类与项目对服务者等于不存在（不能选、不能定价）；
 *   <li><b>只读</b>：服务者改不了目录，想加项目要提提案（F012 / ADR-0034）。
 * </ul>
 *
 * <p>返回体里带着**价格区间**：选品页要把「价格须在 ¥X–¥Y 之间」直接显示出来，
 * 服务者不必先试一次才知道边界在哪。
 */
@RestController
@RequestMapping("/api/v1/provider/catalog")
@Validated
public class ProviderCatalogController {

    private final CatalogQueryService queryService;

    public ProviderCatalogController(CatalogQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/categories")
    public ApiResponse<List<ServiceCategoryView>> categories() {
        CurrentUser.requireDomain(LoginDomain.PROVIDER);
        return ApiResponse.ok(queryService.listCategories(false));
    }

    @GetMapping("/items")
    public ApiResponse<PageResult<ServiceItemView>> items(
            @RequestParam(name = "category_code", required = false) String categoryCode,
            @RequestParam(required = false)
            @Size(max = CatalogQueryService.KEYWORD_MAX_LENGTH,
                    message = "关键字最长 " + CatalogQueryService.KEYWORD_MAX_LENGTH + " 个字符") String keyword,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(name = "page_size", defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = PageResult.MAX_PAGE_SIZE, message = "每页最多 " + PageResult.MAX_PAGE_SIZE + " 条")
            long pageSize) {
        CurrentUser.requireDomain(LoginDomain.PROVIDER);
        // status 写死为启用：让服务者看见停用项只会引诱他去选一个必然失败的项
        return ApiResponse.ok(queryService.pageItems(categoryCode, keyword, ServiceItem.STATUS_ENABLED,
                page, pageSize));
    }
}
