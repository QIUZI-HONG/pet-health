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

    /**
     * 注册：手机号 + 密码建号，直接返回一对令牌（注册即登录，不用再走一次登录）。
     *
     * <p>同一手机号重复注册（含并发）→ <b>40900</b>「该手机号已注册」，引导去登录。
     * 注册成功还会顺手做两件副作用：写审计（用自增 id 当 target_id）与**邀请归因**——
     * 归因时机就是「注册那一刻」，见 {@code InviteService#attribute}。
     */
    @PostMapping("/api/v1/app/auth/register")
    public ApiResponse<TokenPair> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok(accountService.register(request));
    }

    /**
     * 登录：手机号 + 密码，返回一对令牌。
     *
     * <p>有**登录节流**（同一手机号/同一网段连续失败会被挡），所以「密码错」与「试太多次」
     * 是两种答复——前者的用户行为是改密码，后者是等一会儿，文案要能分辨。
     */
    @PostMapping("/api/v1/app/auth/login")
    public ApiResponse<TokenPair> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(accountService.login(request));
    }

    /**
     * 换发令牌：消费掉旧 Refresh，签发新的一对。
     *
     * <p>Refresh 是**一次性**的，新令牌留在**同一个会话族**里——这样一旦检测到重放，
     * 整族一起吊销（ADR-0012）。账号在这期间被禁用或注销时**不给换**，
     * 否则 Refresh 就成了绕过封禁的后门。
     */
    @PostMapping("/api/v1/app/auth/refresh")
    public ApiResponse<TokenPair> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.ok(accountService.refresh(request));
    }

    /**
     * 退出：吊销这个 Refresh 令牌。
     *
     * <p>只吊销传来的那一个，不是整个会话族——多设备登录时「在手机上退出」不该把平板也踢下去。
     */
    @PostMapping("/api/v1/app/auth/logout")
    public ApiResponse<Void> logout(@Valid @RequestBody LogoutRequest request) {
        accountService.logout(request);
        return ApiResponse.ok();
    }

    /** 当前用户资料。用户 id 从令牌取，**不接受调用方传**——那是越权的入口。 */
    @GetMapping("/api/v1/app/users/me")
    public ApiResponse<UserProfile> me() {
        return ApiResponse.ok(accountService.profile(CurrentUser.requireDomain(LoginDomain.APP)));
    }

    /**
     * 更新资料（昵称 / 头像 / 性别）。
     *
     * <p>字段是「传了才改」的语义：没传的保持原值。**头像传空串表示清掉**，
     * 所以它不能用「为 null 就不改」那套判断，见 {@code AccountService#updateProfile}。
     */
    @PutMapping("/api/v1/app/users/me")
    public ApiResponse<UserProfile> updateMe(@Valid @RequestBody UpdateProfileRequest request) {
        return ApiResponse.ok(accountService.updateProfile(CurrentUser.requireDomain(LoginDomain.APP), request));
    }

    /**
     * 切换当前宠物（顶部宠物选择器）。
     *
     * <p>只写「当前是哪一只」，不改宠物本身的任何数据；宠物不属于当前用户时按不存在处理。
     */
    @PutMapping("/api/v1/app/users/me/active-pet")
    public ApiResponse<UserProfile> activatePet(@Valid @RequestBody ActivatePetRequest request) {
        return ApiResponse.ok(accountService.activatePet(CurrentUser.requireDomain(LoginDomain.APP), request.petId()));
    }
}
