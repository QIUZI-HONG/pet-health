package com.pethealth.ai.web;

import com.pethealth.ai.service.AiConsultService;
import com.pethealth.api.app.AiConsultRequest;
import com.pethealth.api.app.AiConsultView;
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

    public AiConsultController(AiConsultService aiConsultService) {
        this.aiConsultService = aiConsultService;
    }

    @PostMapping
    public ApiResponse<AiConsultView> consult(@PathVariable long petId,
                                             @Valid @RequestBody AiConsultRequest request) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(aiConsultService.consult(userId, petId, request));
    }
}
