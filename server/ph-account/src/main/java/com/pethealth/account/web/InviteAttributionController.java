package com.pethealth.account.web;

import com.pethealth.account.service.AccountService;
import com.pethealth.api.app.InviteAttributionRequest;
import com.pethealth.api.app.InviteAttributionView;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.error.ErrorCode;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.security.LoginDomain;
import com.pethealth.common.trace.TraceIds;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端注册归因接口，契约见 contract/app.yaml 的 {@code /invites/attribution}。
 *
 * <p><b>为什么它在本模块（ph-account）而不是邀请域</b>：这条接口是「注册那一刻」的那一次调用——
 * 契约把归因定成注册成功后前端立即调一次（注册请求体不带邀请码），所以它属于**注册流程的收尾**，
 * 而注册流程在本模块。邀请关系本身仍由 ph-privilege 拥有，本类只是一层薄薄的适配
 * （转交 {@code InviteAttributionApi}，见 {@link AccountService#attributeInvite}）。
 *
 * <p>鉴权由 {@code JwtAuthenticationFilter} 负责；归因对象**就是当前登录账号**，
 * 请求里没有「给谁归因」这个字段——没有出口可绕（ADR-0039 第一节）。
 */
@RestController
public class InviteAttributionController {

    private final AccountService accountService;

    public InviteAttributionController(AccountService accountService) {
        this.accountService = accountService;
    }

    /**
     * 提交用户填的邀请码。
     *
     * <p>{@code attributed=false} **不是错误**（HTTP 200 + code 0）：注册照常成功，
     * 只是没建立邀请关系。
     *
     * <p><b>给用户看的那句话在两处，但只有一个来源</b>：{@code data.notice}
     * （契约点名前端直接展示它）与信封的 {@code message}（同一句，方便日志与排查）。
     * 为什么不能只靠 {@code message}：{@code code=0} 时共享请求层只把 {@code data} 交给调用方，
     * 那句话到不了页面（见 contract/common.yaml 的 {@code ApiResponse.message}）——
     * 于是前端只能按 {@code reason} 自己拼一句，文案就有了两份实现。
     * 两句都取自同一个方法（{@code AccountService.noticeOf}），改文案不会漏改一边。
     */
    @PostMapping("/api/v1/app/invites/attribution")
    public ApiResponse<InviteAttributionView> attribute(@Valid @RequestBody InviteAttributionRequest request) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        InviteAttributionView view = accountService.attributeInvite(userId, request);
        // 成功也是「已处理但要说一句」：code 必须是 0，所以两种结果走同一个信封构造
        return handled(view, view.notice());
    }

    /**
     * 成功信封 + 自定义 message。
     *
     * <p>直接用 {@link ApiResponse} 的构造器而不给 ph-common 加一个 {@code ok(data, message)} 重载：
     * 那个重载会被所有模块顺手用上（「成功但要说一句」在本项目只有归因这一处），
     * 而这里的 {@code code} 必须是 0——它是一次**已处理**的业务结果，不是失败。
     */
    private static ApiResponse<InviteAttributionView> handled(InviteAttributionView view, String message) {
        return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), message, view, TraceIds.currentTraceId());
    }
}
