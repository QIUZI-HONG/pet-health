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

    /**
     * 我的宠物列表。
     *
     * <p>{@code deleted=true} 拿的是**回收站**（软删除且还在 30 天恢复期内的那些），
     * 不是「已删除的也一起给我」——正常列表永远不含删掉的宠物。
     */
    @GetMapping
    public ApiResponse<List<PetView>> list(@RequestParam(name = "deleted", defaultValue = "false") boolean deleted) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(petService.list(userId, deleted));
    }

    /**
     * 添加宠物。
     *
     * <p>这是「被邀请人完成建档」的**唯一接线点**（契约 {@code POST /pets}）：邀请关系的推进就挂在这里，
     * 且与建档**同一个事务**——建档成功而关系没推进的话，被邀请人会在观察窗结束时被判「无行为」而无效，
     * 那是查不出来的错（ADR-0039 第一节 / ADR-0046）。一个被邀请人只归因一次，建第二只不再重复。
     */
    @PostMapping
    public ApiResponse<PetView> create(@Valid @RequestBody PetCreateRequest request) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(petService.create(userId, request));
    }

    /** 宠物详情。不是我的按**不存在**处理（40400），免得用 id 探测别人的宠物。 */
    @GetMapping("/{petId}")
    public ApiResponse<PetView> get(@PathVariable long petId) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(petService.get(userId, petId));
    }

    /**
     * 编辑宠物。
     *
     * <p>改「老年专项」的开启与否会**重算当日评分行**——它直接改变总分的计入口径（ADR-0032 决定一），
     * 所以这不是一次纯字段更新。
     */
    @PutMapping("/{petId}")
    public ApiResponse<PetView> update(@PathVariable long petId, @Valid @RequestBody PetUpdateRequest request) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(petService.update(userId, petId, request));
    }

    /**
     * 删除宠物：**软删除**，30 天内可恢复（{@code PetService.RESTORE_WINDOW_DAYS}）。
     *
     * <p>交付文档 2.4 要求「二次确认弹窗」，那在前端；后端这一层保证的是删除可撤销——
     * 以及写审计（operator_id + trace_id，因为这是删除动作）。
     */
    @DeleteMapping("/{petId}")
    public ApiResponse<Void> delete(@PathVariable long petId) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        petService.delete(userId, petId);
        return ApiResponse.ok();
    }

    /**
     * 从回收站恢复。
     *
     * <p>**超过 30 天就恢复不了**，此时返回 40400 且文案里写明这条限制——
     * 而不是让用户以为「再试试就好了」。
     */
    @PostMapping("/{petId}/restore")
    public ApiResponse<PetView> restore(@PathVariable long petId) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(petService.restore(userId, petId));
    }
}
