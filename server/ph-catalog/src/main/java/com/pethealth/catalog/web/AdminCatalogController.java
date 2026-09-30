package com.pethealth.catalog.web;

import com.pethealth.api.catalog.ServiceCategoryRequest;
import com.pethealth.api.admin.SymptomRuleRequest;
import com.pethealth.api.admin.SymptomRuleView;
import com.pethealth.api.catalog.ServiceCategoryView;
import com.pethealth.api.catalog.ServiceItemRequest;
import com.pethealth.api.catalog.ServiceItemStatusRequest;
import com.pethealth.api.catalog.ServiceItemView;
import com.pethealth.catalog.service.CatalogAdminService;
import com.pethealth.catalog.service.CatalogQueryService;
import com.pethealth.catalog.service.SymptomRuleService;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.security.CurrentUser;
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

import java.util.List;

/**
 * 运营后台的标准目录接口，契约见 contract/admin.yaml 的 {@code /catalog/**}。
 *
 * <p>权限口径（交付文档 2.2 + ADR-0037）：目录的**新增与修改属平台运营**，服务者只能「申请」
 * （走 {@code /provider/catalog/item-requests}）；**删除属超级管理员**——本切片不提供删除接口，
 * 停用走状态接口。运营与超管在 token 里还没有区分（后台账号与角色体系未落地），
 * 这一点写在 ADR-0035 的待协调里，不是这里忘了判。
 */
@RestController
@RequestMapping("/api/v1/admin/catalog")
@Validated
public class AdminCatalogController {

    private final CatalogQueryService queryService;
    private final CatalogAdminService adminService;
    private final SymptomRuleService symptomRuleService;

    public AdminCatalogController(CatalogQueryService queryService, CatalogAdminService adminService,
                                  SymptomRuleService symptomRuleService) {
        this.queryService = queryService;
        this.adminService = adminService;
        this.symptomRuleService = symptomRuleService;
    }

    /** 分类列表。{@code include_disabled=true} 时连停用的分类一起给（运营要能看见自己停用过什么）。 */
    @GetMapping("/categories")
    public ApiResponse<List<ServiceCategoryView>> categories(
            @RequestParam(name = "include_disabled", defaultValue = "true") boolean includeDisabled) {
        CurrentUser.requireAdmin();
        return ApiResponse.ok(queryService.listCategories(includeDisabled));
    }

    @PostMapping("/categories")
    public ApiResponse<ServiceCategoryView> createCategory(@Valid @RequestBody ServiceCategoryRequest request) {
        CurrentUser.requireAdmin();
        return ApiResponse.ok(adminService.createCategory(request));
    }

    @PutMapping("/categories/{category_id}")
    public ApiResponse<ServiceCategoryView> updateCategory(@PathVariable("category_id") long categoryId,
                                                           @Valid @RequestBody ServiceCategoryRequest request) {
        CurrentUser.requireAdmin();
        return ApiResponse.ok(adminService.updateCategory(categoryId, request));
    }

    /**
     * 目录项分页。
     *
     * <p>{@code keyword} 有长度上限（{@link CatalogQueryService#KEYWORD_MAX_LENGTH}）：
     * 超长关键字既搜不到东西，又白让数据库做一次全表 `LIKE`。
     */
    @GetMapping("/items")
    public ApiResponse<PageResult<ServiceItemView>> items(
            @RequestParam(name = "category_code", required = false) String categoryCode,
            @RequestParam(required = false)
            @Size(max = CatalogQueryService.KEYWORD_MAX_LENGTH,
                    message = "关键字最长 " + CatalogQueryService.KEYWORD_MAX_LENGTH + " 个字符") String keyword,
            @RequestParam(required = false)
            @Min(value = 0, message = "状态只能是 0（停用）或 1（启用）")
            @Max(value = 1, message = "状态只能是 0（停用）或 1（启用）") Integer status,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(name = "page_size", defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = PageResult.MAX_PAGE_SIZE, message = "每页最多 " + PageResult.MAX_PAGE_SIZE + " 条")
            long pageSize) {
        CurrentUser.requireAdmin();
        return ApiResponse.ok(queryService.pageItems(categoryCode, keyword, status, page, pageSize));
    }

    @PostMapping("/items")
    public ApiResponse<ServiceItemView> createItem(@Valid @RequestBody ServiceItemRequest request) {
        CurrentUser.requireAdmin();
        return ApiResponse.ok(adminService.createItem(request));
    }

    @PutMapping("/items/{item_id}")
    public ApiResponse<ServiceItemView> updateItem(@PathVariable("item_id") long itemId,
                                                   @Valid @RequestBody ServiceItemRequest request) {
        CurrentUser.requireAdmin();
        return ApiResponse.ok(adminService.updateItem(itemId, request));
    }

    /** 启用 / 停用：停用只挡新的选品，不自动下架服务者已上架的服务项（ADR-0034）。 */
    @PutMapping("/items/{item_id}/status")
    public ApiResponse<ServiceItemView> updateItemStatus(@PathVariable("item_id") long itemId,
                                                         @Valid @RequestBody ServiceItemStatusRequest request) {
        CurrentUser.requireAdmin();
        return ApiResponse.ok(adminService.updateItemStatus(itemId, request.status()));
    }

    // ---------------------------------------------------------------- 症状映射（F011 规则版）

    /**
     * 症状映射列表。
     *
     * <p>它管的是「F011 推荐什么」：C 端的 AI 找服务用这张表把症状映射到项目。
     * 症状词必须是**知识侧的规范名**（口语词由 AI 侧的词典归一），所以运营在这一页改的是
     * 「哪个症状推荐哪个项目」，而不是「怎么认症状」——后者在知识库里，有两套词表就会分叉。
     */
    @GetMapping("/symptom-rules")
    public ApiResponse<PageResult<SymptomRuleView>> symptomRules(
            @RequestParam(name = "symptom_keyword", required = false)
            @Size(max = 64, message = "症状名最长 64 个字符") String symptomKeyword,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(name = "page_size", defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = PageResult.MAX_PAGE_SIZE, message = "每页最多 " + PageResult.MAX_PAGE_SIZE + " 条")
            long pageSize) {
        CurrentUser.requireAdmin();
        return ApiResponse.ok(symptomRuleService.page(symptomKeyword, page, pageSize));
    }

    @PostMapping("/symptom-rules")
    public ApiResponse<SymptomRuleView> createSymptomRule(@Valid @RequestBody SymptomRuleRequest request) {
        CurrentUser.requireAdmin();
        return ApiResponse.ok(symptomRuleService.create(request));
    }

    @PutMapping("/symptom-rules/{rule_id}")
    public ApiResponse<SymptomRuleView> updateSymptomRule(@PathVariable("rule_id") long ruleId,
                                                          @Valid @RequestBody SymptomRuleRequest request) {
        CurrentUser.requireAdmin();
        return ApiResponse.ok(symptomRuleService.update(ruleId, request));
    }
}
