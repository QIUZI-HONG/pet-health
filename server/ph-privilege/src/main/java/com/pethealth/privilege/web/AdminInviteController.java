package com.pethealth.privilege.web;

import com.pethealth.api.privilege.InviteDtos;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.security.LoginDomain;
import com.pethealth.privilege.service.InviteAdminService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营后台的邀请接口，契约见 contract/admin.yaml 的 {@code /invites/**}
 * （切片 #111，决策见 ADR-0039 第一节 / ADR-0046）。
 *
 * <p>运营能做三件事：看邀请总览与转化率、按人查邀请关系、配阶梯档位的奖励物。
 * **没有「补一个邀请关系」的入口**：归因只在注册那一刻，补填是刷券的入口
 * ——这条口径在接口层就没有出口，而不是靠运营自觉。
 */
@RestController
@RequestMapping("/api/v1/admin/invites")
@Validated
public class AdminInviteController {

    private final InviteAdminService inviteAdminService;

    public AdminInviteController(InviteAdminService inviteAdminService) {
        this.inviteAdminService = inviteAdminService;
    }

    /** 邀请总览（含阶梯达成；`valid_rate` 是有效邀请转化率，替代 K 因子）。 */
    @GetMapping("/overview")
    public ApiResponse<InviteDtos.InviteOverviewView> overview() {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(inviteAdminService.overview());
    }

    /** 邀请关系分页。 */
    @GetMapping("/relations")
    public ApiResponse<PageResult<InviteDtos.InviteRelationView>> relations(
            @RequestParam(name = "inviter_user_id", required = false) Long inviterUserId,
            @RequestParam(name = "invitee_user_id", required = false) Long inviteeUserId,
            @RequestParam(required = false)
            @Min(value = 1, message = "状态只能是 1 待生效 / 2 有效 / 3 无效")
            @Max(value = 3, message = "状态只能是 1 待生效 / 2 有效 / 3 无效") Integer status,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(name = "page_size", defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = PageResult.MAX_PAGE_SIZE, message = "每页最多 " + PageResult.MAX_PAGE_SIZE + " 条")
            long pageSize) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(inviteAdminService.listRelations(inviterUserId, inviteeUserId, status,
                page, pageSize));
    }

    /** 阶梯档位（门槛固定五档；未配奖励的档位也在列表里——那是有效状态，不是缺数据）。 */
    @GetMapping("/ladder-tiers")
    public ApiResponse<java.util.List<InviteDtos.InviteLadderTierView>> ladderTiers() {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(inviteAdminService.listLadderTiers());
    }

    /** 配置某一档的奖励（门槛不可改；奖励只在达成时发一次，改配置不补发历史）。 */
    @PutMapping("/ladder-tiers/{threshold}")
    public ApiResponse<InviteDtos.InviteLadderTierView> updateLadderTier(
            @PathVariable("threshold") int threshold,
            @Valid @RequestBody InviteDtos.InviteLadderTierRequest request) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(inviteAdminService.updateLadderTier(threshold, request));
    }

    /** 反作弊拦截记录（按判据查询：SELF_INVITE / SAME_DEVICE / SAME_IP_SEGMENT / NO_ACTIVITY_24H）。 */
    @GetMapping("/risk-records")
    public ApiResponse<PageResult<InviteDtos.InviteRiskRecordView>> riskRecords(
            @RequestParam(required = false) String rule,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(name = "page_size", defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = PageResult.MAX_PAGE_SIZE, message = "每页最多 " + PageResult.MAX_PAGE_SIZE + " 条")
            long pageSize) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(inviteAdminService.listRiskRecords(rule, page, pageSize));
    }
}
