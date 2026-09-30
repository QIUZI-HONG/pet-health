package com.pethealth.record.web;

import com.pethealth.api.app.CareModeRequest;
import com.pethealth.api.app.CareModeView;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.security.LoginDomain;
import com.pethealth.record.service.PetService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端专项照护模式接口，契约见 contract/app.yaml 的 {@code /pets/{pet_id}/care-mode}
 * （切片 #116，决策见 ADR-0032）。
 *
 * <p>GET 是**派生结果的读取**（年龄按生日实时算、慢病解除即退出），PUT 是**用户手动关闭**：
 * 关掉之后历史数据保留、老年维不再计入总分，服务端会重算当日评分行。
 */
@RestController
@RequestMapping("/api/v1/app/pets/{petId}/care-mode")
public class CareModeController {

    private final PetService petService;

    public CareModeController(PetService petService) {
        this.petService = petService;
    }

    @GetMapping
    public ApiResponse<CareModeView> get(@PathVariable long petId) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(petService.careMode(userId, petId));
    }

    @PutMapping
    public ApiResponse<CareModeView> update(@PathVariable long petId,
                                           @Valid @RequestBody CareModeRequest request) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(petService.setCareMode(userId, petId, request.enabled()));
    }
}
