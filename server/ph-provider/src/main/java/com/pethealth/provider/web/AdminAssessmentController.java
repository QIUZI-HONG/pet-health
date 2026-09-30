package com.pethealth.provider.web;

import com.pethealth.api.provider.AssessmentDtos;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.security.LoginDomain;
import com.pethealth.provider.service.AssessmentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营后台的考核接口，契约见 contract/admin.yaml 的 {@code /assessments/**}（F022）。
 *
 * <p>五条路径，三块内容：
 *
 * <ul>
 *   <li><b>规则</b>（读 + 整体覆盖）：权重、三项达标线、三档阈值与推荐优先级；
 *   <li><b>看</b>（全平台列表 + 任意一条明细）：改分之前要看得到分是怎么来的；
 *   <li><b>覆盖单项分</b>：ADR-0039 第三节允许超管覆盖，但理由必填、前后值都留痕。
 * </ul>
 *
 * <p>权限分两层，**登录域 admin 只是第一层**：规则配置与覆盖还要过
 * {@code AssessmentSuperAdminGuard}（超管名单，未配置时谁都不能改）——ADR-0037 第一节把这两件事
 * 明确划给超级管理员，而本仓的角色体系还没落地（ADR-0035 的「需要协调」），
 * 这道门禁就是那条口径的临时落点，取舍写在 ADR-0052。
 *
 * <p>读接口不设第二道门禁（运营的日常动作）：看得到、改不了。
 */
@RestController
@RequestMapping("/api/v1/admin")
@Validated
public class AdminAssessmentController {

    private static final int KEYWORD_MAX_LENGTH = 64;

    private final AssessmentService assessmentService;

    public AdminAssessmentController(AssessmentService assessmentService) {
        this.assessmentService = assessmentService;
    }

    /** 当前的考核规则。 */
    @GetMapping("/assessments/rules")
    public ApiResponse<AssessmentDtos.AssessmentRuleView> rules() {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(assessmentService.ruleView());
    }

    /** 整体覆盖考核规则（**只归超级管理员**）：改了只影响之后算出的账期。 */
    @PutMapping("/assessments/rules")
    public ApiResponse<AssessmentDtos.AssessmentRuleView> updateRules(
            @Valid @RequestBody AssessmentDtos.AssessmentRuleRequest request) {
        return ApiResponse.ok(assessmentService.updateRule(request));
    }

    /** 全平台考核列表（按账期 / 等级 / 门店名筛选）。 */
    @GetMapping("/assessments")
    public ApiResponse<PageResult<AssessmentDtos.AssessmentSummaryView>> assessments(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) @Min(value = 1, message = "等级只能是 1/2/3")
            @Max(value = 3, message = "等级只能是 1/2/3") Integer level,
            @RequestParam(required = false) @Size(max = KEYWORD_MAX_LENGTH, message = "关键字最长 64 个字符")
            String keyword,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(name = "page_size", defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = PageResult.MAX_PAGE_SIZE, message = "每页最多 " + PageResult.MAX_PAGE_SIZE + " 条")
            long pageSize) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(assessmentService.listAll(period, level, keyword, page, pageSize));
    }

    /** 考核明细（每项得分、是否参与、数据来源与覆盖留痕）。 */
    @GetMapping("/assessments/{score_id}")
    public ApiResponse<AssessmentDtos.AssessmentView> assessment(
            @PathVariable("score_id") long scoreId) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(assessmentService.get(scoreId));
    }

    /** 覆盖单项分（**理由必填，只归超级管理员**）：覆盖后总分与等级立刻重算。 */
    @PostMapping("/assessments/{score_id}/overrides")
    public ApiResponse<AssessmentDtos.AssessmentView> override(
            @PathVariable("score_id") long scoreId,
            @Valid @RequestBody AssessmentDtos.AssessmentOverrideRequest request) {
        return ApiResponse.ok(assessmentService.override(scoreId, request));
    }
}
