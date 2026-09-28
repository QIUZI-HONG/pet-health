package com.pethealth.account.web;

import com.pethealth.account.service.ComplianceService;
import com.pethealth.api.app.ComplianceDocumentView;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.security.LoginDomain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 合规文档接口，契约见 contract/app.yaml 的 {@code /compliance/documents}。
 *
 * <p>**要登录**：它是 C 端的「我的 → 关于/隐私」入口，不是营销页；而且文档里的占位状态
 * 不该被外部匿名探测。
 */
@RestController
@RequestMapping("/api/v1/app/compliance/documents")
public class ComplianceController {

    private final ComplianceService complianceService;

    public ComplianceController(ComplianceService complianceService) {
        this.complianceService = complianceService;
    }

    @GetMapping
    public ApiResponse<List<ComplianceDocumentView>> list() {
        CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(complianceService.list());
    }

    @GetMapping("/{code}")
    public ApiResponse<ComplianceDocumentView> get(@PathVariable String code) {
        CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(complianceService.get(code));
    }
}
