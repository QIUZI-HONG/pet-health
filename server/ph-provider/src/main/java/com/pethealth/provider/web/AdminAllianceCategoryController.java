package com.pethealth.provider.web;

import com.pethealth.api.provider.AllianceCategoryRequest;
import com.pethealth.api.provider.AllianceCategoryStatusRequest;
import com.pethealth.api.provider.AllianceCategoryView;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.security.LoginDomain;
import com.pethealth.provider.service.AllianceCategoryService;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 运营后台的联盟分类维度维护，契约见 contract/admin.yaml 的 {@code /alliance-categories}。
 *
 * <p>维度列表**不套外壳**（没有分页）：维度是运营维护的短清单（种子三档，实际用起来也就十几档），
 * 分页在这里只会让运营多点一次。换成「可增到几千档」时再补分页——那时列表本身也不该是人看的了。
 *
 * <p>门店归属的指定不在这里，在 {@link AdminProviderReviewController}
 * 的 {@code PUT /providers/{provider_id}/alliance}：它改的是服务者，就该由服务者的控制器负责，
 * 这样「谁改服务者」这件事在代码里只有一个答案。
 */
@RestController
@RequestMapping("/api/v1/admin/alliance-categories")
@Validated
public class AdminAllianceCategoryController {

    private final AllianceCategoryService allianceCategories;

    public AdminAllianceCategoryController(AllianceCategoryService allianceCategories) {
        this.allianceCategories = allianceCategories;
    }

    /** 全部维度（含停用）。停用的仍然返回：它还承载着既有归属，藏起来会让那一列显示成空白。 */
    @GetMapping
    public ApiResponse<List<AllianceCategoryView>> list() {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(allianceCategories.list());
    }

    /** 新增一档。编码与名称都会查重。 */
    @PostMapping
    public ApiResponse<AllianceCategoryView> create(@Valid @RequestBody AllianceCategoryRequest request) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(allianceCategories.create(request));
    }

    /** 改名称 / 说明 / 顺序。**编码不可改**（见服务层注释）。 */
    @PutMapping("/{category_id}")
    public ApiResponse<AllianceCategoryView> update(@PathVariable("category_id") long categoryId,
                                                     @Valid @RequestBody AllianceCategoryRequest request) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(allianceCategories.update(categoryId, request));
    }

    /** 启用 / 停用。停用**不移动既有归属**，只挡住新的指定。 */
    @PutMapping("/{category_id}/status")
    public ApiResponse<AllianceCategoryView> changeStatus(
            @PathVariable("category_id") long categoryId,
            @Valid @RequestBody AllianceCategoryStatusRequest request) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(allianceCategories.changeStatus(categoryId, request));
    }
}
