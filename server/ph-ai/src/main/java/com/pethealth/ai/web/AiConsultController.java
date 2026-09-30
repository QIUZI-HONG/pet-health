package com.pethealth.ai.web;

import com.pethealth.ai.service.AiConsultService;
import com.pethealth.ai.service.HumanConsultService;
import com.pethealth.api.app.AiConsultRequest;
import com.pethealth.api.app.AiConsultView;
import com.pethealth.api.app.HumanConsultView;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.security.LoginDomain;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端 AI 咨询接口，契约见 contract/app.yaml 的 {@code /pets/{pet_id}/ai-consults}。
 *
 * <p>路径挂在宠物下而不是 {@code /ai/consults}：咨询**必然针对某一只宠物**（分级要看品种、年龄、
 * 慢病），挂在宠物下有两点好处：归属校验是天然的，且前端不会出现「忘了传 pet_id」这种调用。
 */
@RestController
@RequestMapping("/api/v1/app/pets/{petId}/ai-consults")
public class AiConsultController {

    private final AiConsultService aiConsultService;
    private final HumanConsultService humanConsultService;

    public AiConsultController(AiConsultService aiConsultService, HumanConsultService humanConsultService) {
        this.aiConsultService = aiConsultService;
        this.humanConsultService = humanConsultService;
    }

    @PostMapping
    public ApiResponse<AiConsultView> consult(@PathVariable long petId,
                                             @Valid @RequestBody AiConsultRequest request) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(aiConsultService.consult(userId, petId, request));
    }

    /**
     * 转人工：把这次咨询交给平台人工跟进（F006 的出口）。
     *
     * <p>**幂等**：一次咨询只能转一次，重复提交返回同一条工单——用户连点两下不该先看到成功再看到失败。
     * 口径与代价（不接支付、不直接派给服务者）写在 {@code HumanConsultService} 的类注释里。
     */
    @PostMapping("/{consult_id}/transfer")
    public ApiResponse<HumanConsultView> transfer(@PathVariable long petId,
                                                 @PathVariable("consult_id") long consultId) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(humanConsultService.transfer(userId, petId, consultId));
    }
}
