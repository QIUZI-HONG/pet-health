package com.pethealth.ai.web;

import com.pethealth.ai.service.AiOpsService;
import com.pethealth.ai.service.HumanConsultService;
import com.pethealth.api.admin.AiOpsDtos;
import com.pethealth.api.admin.HumanConsultAdminView;
import com.pethealth.api.admin.HumanConsultStatusRequest;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * AI 运营接口（切片 #103），路由前缀 {@code /api/v1/admin/**}（登录域必须是 admin）。
 *
 * <p>它服务的是「运营在后台改提示词与红线词即时生效、可一键降级、分级结果可抽检」（#103 的四条
 * 验收标准）。**运营后台的界面不在本切片**（属 #118），所以这一层只保证接口可用、有测试；
 * 界面接上之前，能用的是 curl / 脚本——写在这里免得下一个人以为漏做了界面。
 *
 * <p><b>契约状态</b>：{@code contract/admin.yaml} 的 {@code ai} tag 已按本批接口补写（16 条路径），
 * 字段形状以那份契约与 {@code AiOpsDtos} 的对齐为准。改这里的字段名或形状必须同步改契约，
 * 然后跑 {@code pnpm --filter @pet-health/shared gen:api}（前端类型由契约生成，ADR-0005）。
 * 字段名与 Python 侧读的列一一对应（`ai/app/ops.py`），改这里必须同步改那边。
 *
 * <p>权限：本切片对所有 admin 域身份一视同仁（**运营与超管在 token 里还没有区分**，
 * 后台账号与角色体系未落地，与 {@code AdminCatalogController} 同一口径）。
 * 因此「停用红线」「切降级开关」这类动作的授权粒度属于待办，不是这里忘了判。
 */
@RestController
@RequestMapping("/api/v1/admin/ai")
@Validated
public class AiOpsController {

    private final AiOpsService opsService;
    private final HumanConsultService humanConsultService;

    public AiOpsController(AiOpsService opsService, HumanConsultService humanConsultService) {
        this.opsService = opsService;
        this.humanConsultService = humanConsultService;
    }

    // ---------------------------------------------------------------- 提示词

    @GetMapping("/prompts")
    public ApiResponse<List<AiOpsDtos.PromptTemplateView>> prompts(
            @RequestParam(required = false) String code) {
        CurrentUser.requireAdmin();
        return ApiResponse.ok(opsService.listPrompts(code));
    }

    /** 新建版本。**不要原地改已有的版本**：留痕按版本号归因分级漂移（ADR-0010）。 */
    @PostMapping("/prompts")
    public ApiResponse<AiOpsDtos.PromptTemplateView> createPrompt(
            @Valid @RequestBody AiOpsDtos.PromptTemplateRequest request) {
        CurrentUser.requireAdmin();
        return ApiResponse.ok(opsService.createPrompt(request));
    }

    /** 调灰度 / 启停。改比例就是回滚，不必发版。 */
    @PutMapping("/prompts/{prompt_id}")
    public ApiResponse<AiOpsDtos.PromptTemplateView> updatePrompt(
            @PathVariable("prompt_id") long promptId,
            @Valid @RequestBody AiOpsDtos.PromptTemplateUpdateRequest request) {
        CurrentUser.requireAdmin();
        return ApiResponse.ok(opsService.updatePrompt(promptId, request));
    }

    // ---------------------------------------------------------------- 红线词

    /**
     * 红线词分页。{@code enabled} 是 JSON boolean（true / false），与写接口同一形状。
     *
     * <p>{@code total} 由 {@code PageResult.from} 从 {@code Page.getTotal()} 取——**不是这一页的条数**，
     * 否则 {@code has_more} 恒为 false、运营会以为词表只有第一页（D20 同族，测试守着这条）。
     */
    @GetMapping("/red-flags")
    public ApiResponse<PageResult<AiOpsDtos.RedFlagView>> redFlags(
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(name = "page_size", defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = PageResult.MAX_PAGE_SIZE, message = "每页最多 " + PageResult.MAX_PAGE_SIZE + " 条")
            long pageSize) {
        CurrentUser.requireAdmin();
        return ApiResponse.ok(opsService.listRedFlags(enabled, page, pageSize));
    }

    @PostMapping("/red-flags")
    public ApiResponse<AiOpsDtos.RedFlagView> createRedFlag(
            @Valid @RequestBody AiOpsDtos.RedFlagRequest request) {
        CurrentUser.requireAdmin();
        return ApiResponse.ok(opsService.createRedFlag(request));
    }

    @PutMapping("/red-flags/{red_flag_id}")
    public ApiResponse<AiOpsDtos.RedFlagView> updateRedFlag(
            @PathVariable("red_flag_id") long redFlagId,
            @Valid @RequestBody AiOpsDtos.RedFlagRequest request) {
        CurrentUser.requireAdmin();
        return ApiResponse.ok(opsService.updateRedFlag(redFlagId, request));
    }

    /** 停用（软删）。**不是物理删除**：留痕里引用过它的咨询仍要能查到当时的规则。 */
    @DeleteMapping("/red-flags/{red_flag_id}")
    public ApiResponse<Void> deleteRedFlag(@PathVariable("red_flag_id") long redFlagId) {
        CurrentUser.requireAdmin();
        opsService.deleteRedFlag(redFlagId);
        return ApiResponse.ok(null);
    }

    // ---------------------------------------------------------------- 分级规则

    @GetMapping("/grading-rules")
    public ApiResponse<List<AiOpsDtos.GradingRuleView>> gradingRules(
            @RequestParam(required = false) Boolean enabled) {
        CurrentUser.requireAdmin();
        return ApiResponse.ok(opsService.listGradingRules(enabled));
    }

    @PostMapping("/grading-rules")
    public ApiResponse<AiOpsDtos.GradingRuleView> createGradingRule(
            @Valid @RequestBody AiOpsDtos.GradingRuleRequest request) {
        CurrentUser.requireAdmin();
        return ApiResponse.ok(opsService.createGradingRule(request));
    }

    @PutMapping("/grading-rules/{rule_id}")
    public ApiResponse<AiOpsDtos.GradingRuleView> updateGradingRule(
            @PathVariable("rule_id") long ruleId,
            @Valid @RequestBody AiOpsDtos.GradingRuleRequest request) {
        CurrentUser.requireAdmin();
        return ApiResponse.ok(opsService.updateGradingRule(ruleId, request));
    }

    // ---------------------------------------------------------------- 护栏词表

    @GetMapping("/guard-terms")
    public ApiResponse<List<AiOpsDtos.GuardTermView>> guardTerms(
            @RequestParam(required = false) String kind) {
        CurrentUser.requireAdmin();
        return ApiResponse.ok(opsService.listGuardTerms(kind));
    }

    @PostMapping("/guard-terms")
    public ApiResponse<AiOpsDtos.GuardTermView> createGuardTerm(
            @Valid @RequestBody AiOpsDtos.GuardTermRequest request) {
        CurrentUser.requireAdmin();
        return ApiResponse.ok(opsService.createGuardTerm(request));
    }

    @PutMapping("/guard-terms/{term_id}")
    public ApiResponse<AiOpsDtos.GuardTermView> updateGuardTerm(
            @PathVariable("term_id") long termId,
            @Valid @RequestBody AiOpsDtos.GuardTermRequest request) {
        CurrentUser.requireAdmin();
        return ApiResponse.ok(opsService.updateGuardTerm(termId, request));
    }

    // ---------------------------------------------------------------- 开关

    @GetMapping("/switches")
    public ApiResponse<List<AiOpsDtos.SwitchView>> switches() {
        CurrentUser.requireAdmin();
        return ApiResponse.ok(opsService.listSwitches());
    }

    /** 一键降级：{@code force_rule_only} 打开后全量走规则通道（不调模型）。 */
    @PutMapping("/switches/{switch_code}")
    public ApiResponse<AiOpsDtos.SwitchView> updateSwitch(
            @PathVariable("switch_code") String switchCode,
            @Valid @RequestBody AiOpsDtos.SwitchUpdateRequest request) {
        CurrentUser.requireAdmin();
        return ApiResponse.ok(opsService.updateSwitch(switchCode, request));
    }

    // ---------------------------------------------------------------- 抽检

    /** 按 prompt_version 抽检留痕：改完提示词之后看分级有没有漂（#103 的归因要求）。 */
    @GetMapping("/consults")
    public ApiResponse<PageResult<AiOpsDtos.ConsultAuditView>> consults(
            @RequestParam(name = "prompt_version", required = false) String promptVersion,
            @RequestParam(name = "risk_level", required = false)
            @Min(value = 1, message = "风险等级 1–3")
            @Max(value = 3, message = "风险等级 1–3") Integer riskLevel,
            @RequestParam(required = false) Boolean degraded,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(name = "page_size", defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = PageResult.MAX_PAGE_SIZE, message = "每页最多 " + PageResult.MAX_PAGE_SIZE + " 条")
            long pageSize) {
        CurrentUser.requireAdmin();
        return ApiResponse.ok(opsService.sampleConsults(promptVersion, riskLevel, degraded, page, pageSize));
    }

    // ---------------------------------------------------------------- 转人工工单（F006）

    /** 待办队列：待处理在前、同状态按提交时间升序（先到先处理）。 */
    @GetMapping("/human-consults")
    public ApiResponse<PageResult<HumanConsultAdminView>> humanConsults(
            @RequestParam(required = false)
            @Min(value = 0, message = "状态只能是 0（待处理）/ 1（已回复）/ 2（已关闭）")
            @Max(value = 2, message = "状态只能是 0（待处理）/ 1（已回复）/ 2（已关闭）") Integer status,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(name = "page_size", defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = PageResult.MAX_PAGE_SIZE, message = "每页最多 " + PageResult.MAX_PAGE_SIZE + " 条")
            long pageSize) {
        // 队列是只读查询：访问日志里已按 traceId 记了这条请求，不再单独打点
        CurrentUser.requireAdmin();
        return ApiResponse.ok(humanConsultService.page(status, page, pageSize));
    }

    /** 处置：回复（1，必填回复内容）或关闭（2）。终态不可再改（40900）。 */
    @PutMapping("/human-consults/{ticket_id}")
    public ApiResponse<HumanConsultAdminView> handleHumanConsult(
            @PathVariable("ticket_id") long ticketId,
            @Valid @RequestBody HumanConsultStatusRequest request) {
        long operatorId = CurrentUser.requireAdmin();
        return ApiResponse.ok(humanConsultService.handle(operatorId, ticketId, request));
    }

    // ---------------------------------------------------------------- 知识条目复核（D-12 / ADR-0054）

    /** 知识条目列表（复核用）：只给复核要看的字段，未复核在前。 */
    @GetMapping("/knowledge-entries")
    public ApiResponse<PageResult<AiOpsDtos.AdminKnowledgeEntryView>> knowledgeEntries(
            @RequestParam(name = "review_status", required = false) String reviewStatus,
            @RequestParam(name = "category_code", required = false) String categoryCode,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(name = "page_size", defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = PageResult.MAX_PAGE_SIZE, message = "每页最多 " + PageResult.MAX_PAGE_SIZE + " 条")
            long pageSize) {
        CurrentUser.requireAdmin();
        return ApiResponse.ok(opsService.listKnowledgeEntries(reviewStatus, categoryCode, keyword, page, pageSize));
    }

    /**
     * 复核一条知识条目：`vet`（通过）/ `reject`（打回）。
     *
     * <p>**这是唯一能改 `review_status` 的接口**（ADR-0054）：`vetted` 是一句专业背书，
     * 所以要人工填复核人与资质，其余运营接口仍然碰不到这个字段（ADR-0040 第二节）。
     */
    @PostMapping("/knowledge-entries/{code}/review")
    public ApiResponse<AiOpsDtos.AdminKnowledgeEntryView> reviewKnowledgeEntry(
            @PathVariable("code") String code,
            @Valid @RequestBody AiOpsDtos.KnowledgeReviewRequest request) {
        CurrentUser.requireAdmin();
        return ApiResponse.ok(opsService.reviewKnowledgeEntry(code, request));
    }
    /**
     * AI 用量（按账期 × 模型）：**只给事实**，单价由页面上填（ADR-0050 第五节）。
     */
    @GetMapping("/usage")
    public ApiResponse<AiOpsDtos.AiUsageView> aiUsage(
            @RequestParam(required = false) String period) {
        CurrentUser.requireAdmin();
        return ApiResponse.ok(opsService.aiUsage(period));
    }
}
