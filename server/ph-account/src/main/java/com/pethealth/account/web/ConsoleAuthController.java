package com.pethealth.account.web;

import com.pethealth.account.service.ConsoleAuthService;
import com.pethealth.api.admin.AdminRegisterView;
import com.pethealth.api.app.RegisterRequest;
import com.pethealth.api.app.LoginRequest;
import com.pethealth.api.app.LogoutRequest;
import com.pethealth.api.app.RefreshRequest;
import com.pethealth.api.app.TokenPair;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.security.LoginDomain;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 两个后台的登录/换发/退出，契约见 {@code contract/provider.yaml} 与 {@code admin.yaml}
 * 的 {@code /auth/**}。
 *
 * <p>六个方法都是**一行**：规则全在 {@link ConsoleAuthService} 里（口令校验复用 C 端那一份，
 * 准入判据按域分），控制器只负责把路径上的域填进去。刻意不合并成
 * {@code /api/v1/{domain}/auth/login} 这样的通配路径：路径即登录域，而登录域的判定在
 * {@code JwtAuthenticationFilter} 里是**按前缀比对**的（ADR-0012），通配写法会让那条
 * 前缀规则失去作用面。
 *
 * <p>这六条路径在 {@code JwtAuthenticationFilter.PUBLIC_PATHS} 里（登录当然不能在登录之后）。
 */
@RestController
@RequestMapping("/api/v1")
public class ConsoleAuthController {

    private final ConsoleAuthService consoleAuth;

    public ConsoleAuthController(ConsoleAuthService consoleAuth) {
        this.consoleAuth = consoleAuth;
    }

    // ---------------------------------------------------------------- 服务者后台

    /**
     * 服务者后台登录。
     *
     * <p>**不看名单**：任何启用中的账号都能登——BPM-4 的第一步就是「提交入驻申请」，
     * 那时它还什么资质都没有，如果登不进来就永远提交不了申请。能做什么由入驻状态决定，不是由能不能登决定。
     */
    @PostMapping("/provider/auth/login")
    public ApiResponse<TokenPair> providerLogin(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(consoleAuth.login(LoginDomain.PROVIDER, request));
    }

    /**
     * 服务者后台注册：注册即登录（令牌是 provider 域）。
     *
     * <p>账号与 C 端同一批（ADR-0035）——注册出来的是普通账号，提交入驻申请即成为服务者。
     */
    @PostMapping("/provider/auth/register")
    public ApiResponse<TokenPair> providerRegister(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok(consoleAuth.registerProvider(request));
    }

    @PostMapping("/provider/auth/refresh")
    public ApiResponse<TokenPair> providerRefresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.ok(consoleAuth.refresh(LoginDomain.PROVIDER, request));
    }

    @PostMapping("/provider/auth/logout")
    public ApiResponse<Void> providerLogout(@Valid @RequestBody LogoutRequest request) {
        consoleAuth.logout(request);
        return ApiResponse.ok(null);
    }

    // ---------------------------------------------------------------- 运营后台

    /**
     * 运营后台登录。
     *
     * <p><b>fail-closed 的白名单</b>：账号 id 必须在 {@code CONSOLE_ADMIN_USER_IDS} 里，
     * **名单为空 = 谁都进不去**（不是「谁都能进」）。不这么配的话，忘了配名单的部署会变成
     * 一个对外开放的运营后台——那是最坏的一种默认值。启动日志里有一行 WARN 提醒这件事。
     */
    @PostMapping("/admin/auth/login")
    public ApiResponse<TokenPair> adminLogin(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(consoleAuth.login(LoginDomain.ADMIN, request));
    }

    /**
     * 运营后台注册：**只建账号，不发令牌**（名单制，fail-closed；见 login 的说明）。
     */
    @PostMapping("/admin/auth/register")
    public ApiResponse<AdminRegisterView> adminRegister(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok(consoleAuth.registerAdmin(request));
    }

    @PostMapping("/admin/auth/refresh")
    public ApiResponse<TokenPair> adminRefresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.ok(consoleAuth.refresh(LoginDomain.ADMIN, request));
    }

    @PostMapping("/admin/auth/logout")
    public ApiResponse<Void> adminLogout(@Valid @RequestBody LogoutRequest request) {
        consoleAuth.logout(request);
        return ApiResponse.ok(null);
    }
}
