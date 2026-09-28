package com.pethealth.account.web;

import com.pethealth.account.service.AccountLifecycleService;
import com.pethealth.api.app.AccountExportView;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.security.LoginDomain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账号生命周期接口，契约见 contract/app.yaml 的 {@code /users/me/export} 与 {@code /users/me/deactivation}。
 *
 * <p>与 {@code AccountController} 分开：那两个接口是「日常读写资料」，这两个是**不可逆动作**，
 * 放一起会让「改个昵称」和「注销账号」看起来是同一类操作。
 */
@RestController
@RequestMapping("/api/v1/app/users/me")
public class AccountLifecycleController {

    private final AccountLifecycleService lifecycleService;

    public AccountLifecycleController(AccountLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    @GetMapping("/export")
    public ApiResponse<AccountExportView> export() {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(lifecycleService.export(userId));
    }

    /**
     * 注销。**没有二次确认参数**：确认是界面的事（前端要弹一层），接口做成幂等——
     * 重复调用不报错，这在网络重试下比「第二次返回错误」更符合预期。
     */
    @PostMapping("/deactivation")
    public ApiResponse<Void> deactivate() {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        lifecycleService.deactivate(userId);
        return ApiResponse.ok();
    }
}
