package com.pethealth.privilege.web;

import com.pethealth.api.app.InviteCenterView;
import com.pethealth.api.app.InviteCodeRequest;
import com.pethealth.api.app.InviteCodeView;
import com.pethealth.api.app.PointExchangeRequest;
import com.pethealth.api.app.PointExchangeView;
import com.pethealth.api.app.PointSignInView;
import com.pethealth.api.app.PointsCenterView;
import com.pethealth.api.privilege.CouponDtos;
import com.pethealth.api.privilege.RightsDtos;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.security.LoginDomain;
import com.pethealth.privilege.service.AppGrowthConsole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端增长链路的四个出口，契约见 contract/app.yaml 的 {@code /coupons}、{@code /points}、
 * {@code /points/exchange}、{@code /rights}、{@code /invites/code}（切片 #110–#113 的 C 端半边）。
 *
 * <p>为什么都在 ph-privilege：数据是它的（券 / 积分 / 权益 / 邀请），而 {@code /points/exchange}
 * 还是**写**（扣分 + 发券同一事务）——放到别的模块就变成「别的模块改它的数据」（ADR-0006）。
 *
 * <p>权限：全是**登录用户（C 端）**，没有管理员与技师的位置；每个方法都 `requireDomain(APP)`，
 * 后台令牌拿不到这些数据。金额相关的一个字都没有：券是到店抵扣凭证、积分只能兑券（ADR-0036）。
 */
@RestController
@RequestMapping("/api/v1/app")
@Validated
public class AppGrowthController {

    private final AppGrowthConsole console;

    public AppGrowthController(AppGrowthConsole console) {
        this.console = console;
    }

    /** 我的券（分页 + 状态 / 来源筛选）。{@code provider_name} 是**核销门店**，平台补贴券为空。 */
    @GetMapping("/coupons")
    public ApiResponse<PageResult<CouponDtos.CouponView>> myCoupons(
            @RequestParam(required = false)
            @Min(value = 1, message = "状态只能是 1–4")
            @Max(value = 4, message = "状态只能是 1–4") Integer status,
            @RequestParam(required = false)
            @Min(value = 1, message = "来源只能是 1–5")
            @Max(value = 5, message = "来源只能是 1–5") Integer source,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(name = "page_size", defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = PageResult.MAX_PAGE_SIZE, message = "每页最多 " + PageResult.MAX_PAGE_SIZE + " 条")
            long pageSize) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(console.listMyCoupons(userId, status, source, page, pageSize));
    }

    /** 积分中心：账户 + 任务进度 + 行为分值表 + 兑换档位。 */
    @GetMapping("/points")
    public ApiResponse<PointsCenterView> pointsCenter() {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(console.pointsCenter(userId));
    }

    /**
     * 用积分兑换券：扣分与发券在**同一个事务**里（ADR-0046 第六节）。
     *
     * <p>余额不足 → 40900（整笔不发）；档位不存在或已停用 → 40400；
     * 幂等只能靠 `Idempotency-Key`（ADR-0028），这个头在兑换上是必须带的。
     */
    @PostMapping("/points/exchange")
    public ApiResponse<PointExchangeView> exchange(@Valid @RequestBody PointExchangeRequest request) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(console.exchange(userId, request.optionId()));
    }

    /**
     * 每日签到：记一次 `SIGN_IN` 行为。
     *
     * <p>**不要求 Idempotency-Key**：引用带业务日期、且该行为的每日上限是 1，
     * 所以「今天已经签过」是 200 + `awarded=false`，不是错误（契约里写明了）。
     */
    @PostMapping("/points/sign-in")
    public ApiResponse<PointSignInView> signIn() {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(console.signIn(userId));
    }

    /** 我的权益（每个码：是否生效 + 来源 + 到期；不生效的码也列出）。 */
    @GetMapping("/rights")
    public ApiResponse<RightsDtos.RightsEvaluationView> rights() {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(console.rights(userId));
    }

    /** 我的邀请码与邀请进度（进度按**有效**邀请数算）。 */
    @GetMapping("/invites/code")
    public ApiResponse<InviteCenterView> inviteCenter() {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(console.inviteCenter(userId));
    }

    /**
     * 生成（或取回）我的邀请码。**一人一码**：已有码时返回那一条，
     * 所以 {@code channel} 与 {@code device_id} 记的都是**首次生成时**的值（ADR-0049 §八），
     * 不参与归因与统计——后者只做反作弊判据（{@code SAME_DEVICE} 的比较基准）。
     */
    @PostMapping("/invites/code")
    public ApiResponse<InviteCodeView> createInviteCode(@Valid @RequestBody(required = false)
                                                        InviteCodeRequest request) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(console.createInviteCode(userId,
                request == null ? null : request.channel(),
                request == null ? null : request.deviceId()));
    }

    /**
     * 锁定券（下单前的显式占用，券包页的「用这张券」）。
     *
     * <p>幂等：重复锁定返回同样的结果；已被别的订单占用 / 已过期 → **80001**，已核销 → **80002**，
     * 不属于我 → 40400。锁与下单链路的锁是同一件事的两条入口——先在这里锁、再下单，
     * 下单会把持有者换成真实订单（{@code CouponService.STANDALONE_HOLDER} 的说明）。
     */
    @PostMapping("/coupons/{coupon_id}/lock")
    public ApiResponse<CouponDtos.CouponView> lockCoupon(@PathVariable("coupon_id") long couponId) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(console.lockCoupon(userId, couponId));
    }

    /**
     * 释放券（取消订单时 / 券包页的「释放」）。
     *
     * <p>只放**锁定的、未核销的**券：已核销 → 80002；被**未结束的订单**持有 → **40900**
     * （先取消那一单）；本来就待使用 → 幂等成功。
     */
    @PostMapping("/coupons/{coupon_id}/release")
    public ApiResponse<CouponDtos.CouponView> releaseCoupon(@PathVariable("coupon_id") long couponId) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(console.releaseCoupon(userId, couponId));
    }
}
