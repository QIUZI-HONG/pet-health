package com.pethealth.provider.web;

import com.pethealth.api.provider.AssessmentDtos;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.api.PageResult;
import com.pethealth.provider.service.AssessmentService;
import com.pethealth.provider.service.ProviderAccess;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务者后台的考核接口，契约见 contract/provider.yaml 的 {@code /assessments/**}（F022）。
 *
 * <p>两个接口，都是**读自己的**：列表（跨月可对比）与某月明细（每项得分、是否参与、数据来源）。
 * 写动作一个都没有——规则配置与单项分覆盖在运营后台（admin 域），而且只归超级管理员
 * （[ADR-0037] 第一节的矩阵）。服务者能做的只有「看」与「申诉」，所以覆盖留痕也下发给他。
 *
 * <p>权限：技师也能看（{@code ProviderAccess.requireBound}，权限矩阵里技师那列是「看团队」）——
 * 他参与履约，理应看得到本店的分怎么来的。
 */
@RestController
@RequestMapping("/api/v1/provider")
@Validated
public class ProviderAssessmentController {

    private final AssessmentService assessmentService;
    private final ProviderAccess access;

    public ProviderAssessmentController(AssessmentService assessmentService, ProviderAccess access) {
        this.assessmentService = assessmentService;
        this.access = access;
    }

    /** 我的月度考核列表（账期倒序；可按账期过滤）。 */
    @GetMapping("/assessments")
    public ApiResponse<PageResult<AssessmentDtos.AssessmentSummaryView>> assessments(
            @RequestParam(required = false) String period,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(name = "page_size", defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = PageResult.MAX_PAGE_SIZE, message = "每页最多 " + PageResult.MAX_PAGE_SIZE + " 条")
            long pageSize) {
        return ApiResponse.ok(assessmentService.listMine(access.currentUserId(), period, page, pageSize));
    }

    /** 某月考核明细（三项 + 过程子项 + 覆盖留痕）。 */
    @GetMapping("/assessments/{period}")
    public ApiResponse<AssessmentDtos.AssessmentView> assessment(
            @PathVariable("period") String period) {
        return ApiResponse.ok(assessmentService.getMine(access.currentUserId(), period));
    }
}
