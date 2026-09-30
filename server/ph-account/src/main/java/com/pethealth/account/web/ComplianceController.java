package com.pethealth.account.web;

import com.pethealth.account.service.ComplianceService;
import com.pethealth.api.app.ComplianceDocumentView;
import com.pethealth.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 合规文档接口，契约见 contract/app.yaml 的 {@code /compliance/documents}。
 *
 * <p><b>免登录</b>（2026-09-30 验收的 F027 改过来）：这一页原先写着「要登录——它不是营销页，
 * 而且占位状态不该被外部匿名探测」，但那条理由站不住：**隐私政策与用户协议必须在用户注册前
 * 读得到**，否则注册页上那句「我已阅读并同意」没有依据（交付文档 2.5 的合规口径）。
 * 登录页上就挂着这两个链接，未登录点进去只看到「需要先登录」，等于把同意的前提藏了起来。
 *
 * <p>「占位状态不被探测」也不构成理由：`is_placeholder` 存在的意义就是**让人知道它还没定稿**，
 * 前端会明确显示「待法务定稿，不作为生效条款」。真正需要守住的边界是**账号操作**
 * （导出、注销）——那两条仍然要登录（{@code AccountLifecycleController}）。
 *
 * <p>放行分两处：{@link JwtAuthenticationFilter} 的免登录只读路由（只认 GET），以及本类
 * 不再自己校验登录域。两处缺一不可——过滤器放行而控制器再拦一次，症状就是
 * 「路由看起来放开了、接口还是 40100」。
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
        return ApiResponse.ok(complianceService.list());
    }

    @GetMapping("/{code}")
    public ApiResponse<ComplianceDocumentView> get(@PathVariable String code) {
        return ApiResponse.ok(complianceService.get(code));
    }
}
