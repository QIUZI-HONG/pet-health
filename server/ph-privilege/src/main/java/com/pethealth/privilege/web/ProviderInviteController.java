package com.pethealth.privilege.web;

import com.pethealth.api.provider.ProviderInviteCodeView;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.security.LoginDomain;
import com.pethealth.privilege.service.ProviderInviteCodeService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务者后台的门店推广码，契约见 contract/provider.yaml 的 {@code /invite-code}。
 *
 * <p>这是**营销中心**那一页缺接口的那一半：交付文档 P027 要求「物料 / 推广码 / 活动」，
 * 而服务者侧一直没有拉新入口（ADR-0052「需要协调」第 1 条）。有了它，门店才能把码印成桌贴，
 * 扫它注册的用户归因到门店，考核的拉新项也才有数。
 *
 * <p><b>为什么 GET 不写库、取码要 POST</b>：一个会写库的 GET 是最容易被误触的接口形态
 * （预取、重试、浏览器预读都会命中它）。所以「有没有码」是读，第一次取码是一个明确的 POST。
 */
@RestController
@RequestMapping("/api/v1/provider/invite-code")
@Validated
public class ProviderInviteController {

    private final ProviderInviteCodeService inviteCodes;

    public ProviderInviteController(ProviderInviteCodeService inviteCodes) {
        this.inviteCodes = inviteCodes;
    }

    /** 我的推广码与拉新战况。**还没生成过时 `code` 为 null**（不是 404）——那是正常状态。 */
    @GetMapping
    public ApiResponse<ProviderInviteCodeView> view() {
        CurrentUser.requireDomain(LoginDomain.PROVIDER);
        return ApiResponse.ok(inviteCodes.viewInviteCode());
    }

    /** 生成推广码，**幂等**：重复调用返回同一个码。 */
    @PostMapping
    public ApiResponse<ProviderInviteCodeView> ensure() {
        CurrentUser.requireDomain(LoginDomain.PROVIDER);
        return ApiResponse.ok(inviteCodes.ensure());
    }
}
