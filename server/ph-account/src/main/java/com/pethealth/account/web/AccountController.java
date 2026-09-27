package com.pethealth.account.web;

import com.pethealth.account.service.AccountService;
import com.pethealth.api.app.ActivatePetRequest;
import com.pethealth.api.app.LoginRequest;
import com.pethealth.api.app.LogoutRequest;
import com.pethealth.api.app.RefreshRequest;
import com.pethealth.api.app.RegisterRequest;
import com.pethealth.api.app.TokenPair;
import com.pethealth.api.app.UpdateProfileRequest;
import com.pethealth.api.app.UserProfile;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.security.LoginDomain;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端账号接口：注册 / 登录 / 刷新 / 退出 + 当前用户资料，契约见 contract/app.yaml。
 *
 * <p>鉴权（谁能进来）由 {@code JwtAuthenticationFilter} 负责，这里只管业务；
 * 每个方法从 {@link CurrentUser} 取当前用户，**不接受调用方传 userId**——那是越权的入口。
 */
@RestController
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping("/api/v1/app/auth/register")
    public ApiResponse<TokenPair> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok(accountService.register(request));
    }

    @PostMapping("/api/v1/app/auth/login")
    public ApiResponse<TokenPair> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(accountService.login(request));
    }

    @PostMapping("/api/v1/app/auth/refresh")
    public ApiResponse<TokenPair> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.ok(accountService.refresh(request));
    }

    @PostMapping("/api/v1/app/auth/logout")
    public ApiResponse<Void> logout(@Valid @RequestBody LogoutRequest request) {
        accountService.logout(request);
        return ApiResponse.ok();
    }

    @GetMapping("/api/v1/app/users/me")
    public ApiResponse<UserProfile> me() {
        return ApiResponse.ok(accountService.profile(CurrentUser.requireDomain(LoginDomain.APP)));
    }

    @PutMapping("/api/v1/app/users/me")
    public ApiResponse<UserProfile> updateMe(@Valid @RequestBody UpdateProfileRequest request) {
        return ApiResponse.ok(accountService.updateProfile(CurrentUser.requireDomain(LoginDomain.APP), request));
    }

    @PutMapping("/api/v1/app/users/me/active-pet")
    public ApiResponse<UserProfile> activatePet(@Valid @RequestBody ActivatePetRequest request) {
        return ApiResponse.ok(accountService.activatePet(CurrentUser.requireDomain(LoginDomain.APP), request.petId()));
    }
}
