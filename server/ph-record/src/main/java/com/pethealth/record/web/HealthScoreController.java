package com.pethealth.record.web;

import com.pethealth.api.app.HealthScoreView;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.security.LoginDomain;
import com.pethealth.record.service.HealthScoreService;
import com.pethealth.record.service.PetService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端健康评分接口，契约见 contract/app.yaml 的 {@code /pets/{pet_id}/health-score}。
 *
 * <p>评分本身不落库（查询时现算，保证与最新记录一致）；落库的是打卡时写入的当日快照，
 * 供趋势与周报使用（ADR-0018）。
 */
@RestController
@RequestMapping("/api/v1/app/pets/{petId}/health-score")
public class HealthScoreController {

    private final PetService petService;
    private final HealthScoreService healthScoreService;

    public HealthScoreController(PetService petService, HealthScoreService healthScoreService) {
        this.petService = petService;
        this.healthScoreService = healthScoreService;
    }

    @GetMapping
    public ApiResponse<HealthScoreView> current(@PathVariable long petId) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        // 先过归属校验（别人的宠物 404），再算分
        return ApiResponse.ok(healthScoreService.evaluate(petService.requireOwned(userId, petId)));
    }
}
