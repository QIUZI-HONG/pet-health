package com.pethealth.privilege.web;

import com.pethealth.api.privilege.RightsDtos;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.security.LoginDomain;
import com.pethealth.privilege.service.RightsAdminService;
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

import java.util.List;

/**
 * 运营后台的权益接口，契约见 contract/admin.yaml 的 {@code /rights/**}
 * （切片 #112，决策见 ADR-0038 第三节 / ADR-0045）。
 *
 * <p>四个动作：维护码表、查授予记录、手动授予（**订阅**只能线下签约 + 后台标记，
 * ADR-0036 之后它没有支付载体）、回收一条授予。
 *
 * <p>判定视图 {@code GET /rights/users/{user_id}} 是客服排查的入口——它走的是
 * **各模块共用的那一份判定**（{@code RightsApi.evaluate}），不在这里另写一套规则：
 * 否则客服看到的和用户实际得到的是两个答案。
 */
@RestController
@RequestMapping("/api/v1/admin/rights")
@Validated
public class AdminRightsController {

    private final RightsAdminService rightsAdminService;

    public AdminRightsController(RightsAdminService rightsAdminService) {
        this.rightsAdminService = rightsAdminService;
    }

    /** 权益码表（含停用；新码默认不生效，判定逻辑仍在各模块代码里）。 */
    @GetMapping("/codes")
    public ApiResponse<List<RightsDtos.RightsCodeView>> codes() {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(rightsAdminService.listCodes());
    }

    /** 新增权益码。 */
    @PostMapping("/codes")
    public ApiResponse<RightsDtos.RightsCodeView> createCode(
            @Valid @RequestBody RightsDtos.RightsCodeRequest request) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(rightsAdminService.createCode(request));
    }

    /** 修改权益码（编码不可改；停用只挡新授予，已有授予照常生效）。 */
    @PutMapping("/codes/{code}")
    public ApiResponse<RightsDtos.RightsCodeView> updateCode(
            @PathVariable("code") String code,
            @Valid @RequestBody RightsDtos.RightsCodeRequest request) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(rightsAdminService.updateCode(code, request));
    }

    /** 授予记录分页。 */
    @GetMapping("/grants")
    public ApiResponse<PageResult<RightsDtos.RightsGrantView>> grants(
            @RequestParam(name = "user_id", required = false) Long userId,
            @RequestParam(required = false) String code,
            @RequestParam(required = false)
            @Min(value = 1, message = "状态只能是 1 生效 / 2 已回收")
            @Max(value = 2, message = "状态只能是 1 生效 / 2 已回收") Integer status,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(name = "page_size", defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = PageResult.MAX_PAGE_SIZE, message = "每页最多 " + PageResult.MAX_PAGE_SIZE + " 条")
            long pageSize) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(rightsAdminService.listGrants(userId, code, status, page, pageSize));
    }

    /** 手动授予一条权益（订阅标记 / 客诉补偿；同一来源 + 引用 + 码只授予一次）。 */
    @PostMapping("/grants")
    public ApiResponse<RightsDtos.RightsGrantView> grant(
            @Valid @RequestBody RightsDtos.RightsGrantRequest request) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(rightsAdminService.grant(request));
    }

    /** 回收一条授予（**只回收这一条**：订阅到期不触碰邀请得的永久权益）。 */
    @DeleteMapping("/grants/{grant_id}")
    public ApiResponse<RightsDtos.RightsGrantView> revoke(@PathVariable("grant_id") long grantId) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(rightsAdminService.revoke(grantId));
    }

    /** 某用户的权益判定（每个码：是否生效 + 来源 + 到期；不生效的码也列出来）。 */
    @GetMapping("/users/{user_id}")
    public ApiResponse<RightsDtos.RightsEvaluationView> evaluation(@PathVariable("user_id") long userId) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(rightsAdminService.evaluationOf(userId));
    }
}
