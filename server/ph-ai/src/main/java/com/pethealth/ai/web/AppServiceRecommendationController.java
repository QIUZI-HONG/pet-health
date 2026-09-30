package com.pethealth.ai.web;

import com.pethealth.ai.service.ServiceRecommendationService;
import com.pethealth.api.app.ServiceRecommendationRequest;
import com.pethealth.api.app.ServiceRecommendationView;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.security.LoginDomain;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F011「AI 帮我找服务」：描述症状 → 推荐服务项目（规则版），
 * 契约见 contract/app.yaml 的 {@code /service-recommendations}。
 *
 * <p><b>需要登录</b>（与其它 AI 功能一致）：推荐本身不含隐私数据，但它是一次 AI 侧调用、
 * 且属于「AI 功能」的一部分——门槛与 AI 咨询保持一致，用户不必猜「哪些功能要登录」。
 * 免登录的只有纯浏览那几条（ADR-0037 第一节）。
 *
 * <p><b>用 POST 承载一次「查」</b>：描述是几百字的自由文本，塞进查询串会被长度与编码两头夹住。
 * 它**不是写操作**——不落 AI 留痕、不占 AI 免费额度（那是「咨询」的账），也改不了任何状态。
 */
@RestController
@RequestMapping("/api/v1/app/service-recommendations")
public class AppServiceRecommendationController {

    private final ServiceRecommendationService recommendationService;

    public AppServiceRecommendationController(ServiceRecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    /** 推荐结果可能为空（没认出症状）——那是正常结果，不是错误。 */
    @PostMapping
    public ApiResponse<ServiceRecommendationView> recommend(@Valid @RequestBody ServiceRecommendationRequest request) {
        CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(recommendationService.recommend(request.text()));
    }
}
