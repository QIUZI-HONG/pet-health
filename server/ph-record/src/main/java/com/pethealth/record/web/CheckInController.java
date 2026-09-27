package com.pethealth.record.web;

import com.pethealth.api.app.CheckInDay;
import com.pethealth.api.app.CheckInStreak;
import com.pethealth.api.app.CheckInSubmitRequest;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.security.LoginDomain;
import com.pethealth.record.service.CheckInService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * C 端打卡接口，契约见 contract/app.yaml 的 {@code /pets/{pet_id}/check-ins}。
 *
 * <p>登录身份由过滤器放进 {@link CurrentUser}；每个方法第一步取 userId，所有查询按它过滤——
 * 这是「越权防护」在本模块的落点（别人的宠物一律 404，见 CheckInService）。
 */
@RestController
@RequestMapping("/api/v1/app/pets/{petId}/check-ins")
public class CheckInController {

    private final CheckInService checkInService;

    public CheckInController(CheckInService checkInService) {
        this.checkInService = checkInService;
    }

    @GetMapping
    public ApiResponse<CheckInDay> day(
            @PathVariable long petId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(checkInService.day(userId, petId, date));
    }

    @PostMapping
    public ApiResponse<CheckInDay> submit(@PathVariable long petId,
                                         @Valid @RequestBody CheckInSubmitRequest request) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(checkInService.submit(userId, petId, request));
    }

    @DeleteMapping("/item")
    public ApiResponse<CheckInDay> undo(
            @PathVariable long petId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam int category) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(checkInService.undo(userId, petId, date, category));
    }

    @GetMapping("/streak")
    public ApiResponse<CheckInStreak> streak(@PathVariable long petId) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(checkInService.streak(userId, petId));
    }
}
