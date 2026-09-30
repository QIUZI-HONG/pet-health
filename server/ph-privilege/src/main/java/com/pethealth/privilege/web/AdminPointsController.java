package com.pethealth.privilege.web;

import com.pethealth.api.privilege.PointsDtos;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.security.LoginDomain;
import com.pethealth.privilege.service.PointsAdminService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
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
 * 运营后台的积分接口，契约见 contract/admin.yaml 的 {@code /points/**}
 * （切片 #113，决策见 ADR-0038 第四节 / ADR-0046）。
 *
 * <p>五组配置都是**业务可调项入库**（ADR-0010 的分层）：积分规则设置（每日上限）、
 * 行为分值、任务清单、兑换档位、月度阶梯档位。运营改它们不需要发版。
 *
 * <p>两条边界：
 *
 * <ul>
 *   <li><b>行为码不能新增</b>：行为要有代码去触发它，加一行数据只会产生一个永远不会发生的
 *       动作——新增行为是代码变更；
 *   <li><b>兑换与阶梯只能配平台补贴券</b>：它们的成本归平台，不消耗服务者的贡献额度，
 *       否则等于把兑换成本转嫁给服务者（ADR-0038 第四节）。
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/points")
@Validated
public class AdminPointsController {

    private final PointsAdminService pointsAdminService;

    public AdminPointsController(PointsAdminService pointsAdminService) {
        this.pointsAdminService = pointsAdminService;
    }

    /** 积分总览（含今日发放与每日上限的实际命中情况）。 */
    @GetMapping("/overview")
    public ApiResponse<PointsDtos.PointsOverviewView> overview() {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(pointsAdminService.overview());
    }

    /** 积分流水（含变动后余额，可按用户与行为过滤）。 */
    @GetMapping("/records")
    public ApiResponse<PageResult<PointsDtos.PointRecordView>> records(
            @RequestParam(name = "user_id", required = false) Long userId,
            @RequestParam(name = "behavior_code", required = false) String behaviorCode,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(name = "page_size", defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = PageResult.MAX_PAGE_SIZE, message = "每页最多 " + PageResult.MAX_PAGE_SIZE + " 条")
            long pageSize) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(pointsAdminService.listRecords(userId, behaviorCode, page, pageSize));
    }

    /** 规则设置（每日获取上限；邀请与一次性项不占这个上限）。 */
    @GetMapping("/settings")
    public ApiResponse<PointsDtos.PointsSettingsView> settings() {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(pointsAdminService.settings());
    }

    @PutMapping("/settings")
    public ApiResponse<PointsDtos.PointsSettingsView> updateSettings(
            @Valid @RequestBody PointsDtos.PointsSettingsRequest request) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(pointsAdminService.updateSettings(request));
    }

    /** 行为分值表（ADR-0038 第四节那张表）。 */
    @GetMapping("/behaviors")
    public ApiResponse<List<PointsDtos.PointBehaviorView>> behaviors() {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(pointsAdminService.listBehaviors());
    }

    /** 改行为分值 / 频次 / 启停（**不能新增行为**）。 */
    @PutMapping("/behaviors/{behavior_code}")
    public ApiResponse<PointsDtos.PointBehaviorView> updateBehavior(
            @PathVariable("behavior_code") String behaviorCode,
            @Valid @RequestBody PointsDtos.PointBehaviorRequest request) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(pointsAdminService.updateBehavior(behaviorCode, request));
    }

    /** 任务清单（每日 + 每周两档）。 */
    @GetMapping("/tasks")
    public ApiResponse<List<PointsDtos.PointTaskView>> tasks() {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(pointsAdminService.listTasks());
    }

    /** 新增任务（引用已有行为码；任务本身不额外发分）。 */
    @PostMapping("/tasks")
    public ApiResponse<PointsDtos.PointTaskView> createTask(
            @Valid @RequestBody PointsDtos.PointTaskRequest request) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(pointsAdminService.createTask(request));
    }

    /** 修改任务（编码不可改）。 */
    @PutMapping("/tasks/{task_id}")
    public ApiResponse<PointsDtos.PointTaskView> updateTask(
            @PathVariable("task_id") long taskId,
            @Valid @RequestBody PointsDtos.PointTaskRequest request) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(pointsAdminService.updateTask(taskId, request));
    }

    /** 兑换档位（只兑平台补贴券）。 */
    @GetMapping("/exchange-options")
    public ApiResponse<List<PointsDtos.PointExchangeOptionView>> exchangeOptions() {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(pointsAdminService.listExchangeOptions());
    }

    /** 新增兑换档位（券模板必须是平台补贴券）。 */
    @PostMapping("/exchange-options")
    public ApiResponse<PointsDtos.PointExchangeOptionView> createExchangeOption(
            @Valid @RequestBody PointsDtos.PointExchangeOptionRequest request) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(pointsAdminService.createExchangeOption(request));
    }

    /** 修改兑换档位。 */
    @PutMapping("/exchange-options/{option_id}")
    public ApiResponse<PointsDtos.PointExchangeOptionView> updateExchangeOption(
            @PathVariable("option_id") long optionId,
            @Valid @RequestBody PointsDtos.PointExchangeOptionRequest request) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(pointsAdminService.updateExchangeOption(optionId, request));
    }

    /** 月度阶梯档位（F018 骨架；**种子里没有档位**——门槛与奖励不编）。 */
    @GetMapping("/ladder-tiers")
    public ApiResponse<List<PointsDtos.PointLadderTierView>> ladderTiers() {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(pointsAdminService.listLadderTiers());
    }

    /** 新增阶梯档位（券模板必须是平台补贴券）。 */
    @PostMapping("/ladder-tiers")
    public ApiResponse<PointsDtos.PointLadderTierView> createLadderTier(
            @Valid @RequestBody PointsDtos.PointLadderTierRequest request) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(pointsAdminService.createLadderTier(request));
    }

    /** 修改阶梯档位。 */
    @PutMapping("/ladder-tiers/{tier_id}")
    public ApiResponse<PointsDtos.PointLadderTierView> updateLadderTier(
            @PathVariable("tier_id") long tierId,
            @Valid @RequestBody PointsDtos.PointLadderTierRequest request) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(pointsAdminService.updateLadderTier(tierId, request));
    }
}
