package com.pethealth.record.web;

import com.pethealth.api.app.HealthReportView;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.security.LoginDomain;
import com.pethealth.record.service.HealthReportService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端健康报告接口，契约见 contract/app.yaml 的 {@code /pets/{pet_id}/health-reports}
 * （切片 #115，决策见 ADR-0031）。
 *
 * <p>只有一个读接口：报告按期生成（定时任务 + 读时惰性补齐），**客户端不能自己造一份报告**——
 * 报告是派生视图，它不是用户能写的东西。
 *
 * <p>`type` 的取值来自契约（enum 1/2）：早先同类接口只靠 service 里静默夹取，越界请求
 * 「看起来成功了」但返回的是别的东西（MessageController 的注释里记过这个坑），所以这里显式校验。
 */
@RestController
@RequestMapping("/api/v1/app/pets/{petId}/health-reports")
@Validated
public class HealthReportController {

    private final HealthReportService healthReportService;

    public HealthReportController(HealthReportService healthReportService) {
        this.healthReportService = healthReportService;
    }

    @GetMapping
    public ApiResponse<PageResult<HealthReportView>> list(
            @PathVariable long petId,
            @RequestParam(required = false)
            @Min(value = 1, message = "报告类型只能是 1（周报）或 2（月报）")
            @Max(value = 2, message = "报告类型只能是 1（周报）或 2（月报）") Integer type,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(name = "page_size", defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = PageResult.MAX_PAGE_SIZE, message = "每页最多 " + PageResult.MAX_PAGE_SIZE + " 条")
            long pageSize) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(healthReportService.list(userId, petId, type, page, pageSize));
    }
}
