package com.pethealth.record.web;

import com.pethealth.api.app.PetCreateRequest;
import com.pethealth.api.app.PetUpdateRequest;
import com.pethealth.api.app.PetView;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.security.LoginDomain;
import com.pethealth.record.service.PetService;
import jakarta.validation.Valid;
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
 * C 端宠物档案接口，契约见 contract/app.yaml 的 {@code /pets}。
 *
 * <p>登录身份由过滤器放进 {@link CurrentUser}，这里不再解析 Token；
 * 每个方法第一步都拿 userId——所有查询按它过滤，这是「越权防护」在本模块的落点。
 */
@RestController
@RequestMapping("/api/v1/app/pets")
public class PetController {

    private final PetService petService;

    public PetController(PetService petService) {
        this.petService = petService;
    }

    @GetMapping
    public ApiResponse<List<PetView>> list(@RequestParam(name = "deleted", defaultValue = "false") boolean deleted) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(petService.list(userId, deleted));
    }

    @PostMapping
    public ApiResponse<PetView> create(@Valid @RequestBody PetCreateRequest request) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(petService.create(userId, request));
    }

    @GetMapping("/{petId}")
    public ApiResponse<PetView> get(@PathVariable long petId) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(petService.get(userId, petId));
    }

    @PutMapping("/{petId}")
    public ApiResponse<PetView> update(@PathVariable long petId, @Valid @RequestBody PetUpdateRequest request) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(petService.update(userId, petId, request));
    }

    @DeleteMapping("/{petId}")
    public ApiResponse<Void> delete(@PathVariable long petId) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        petService.delete(userId, petId);
        return ApiResponse.ok();
    }

    @PostMapping("/{petId}/restore")
    public ApiResponse<PetView> restore(@PathVariable long petId) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(petService.restore(userId, petId));
    }
}
