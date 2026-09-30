package com.pethealth.record.web;

import com.pethealth.api.app.CheckInDay;
import com.pethealth.api.app.CheckInStreak;
import com.pethealth.api.app.CheckInSubmitRequest;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.time.AppTime;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.security.LoginDomain;
import com.pethealth.record.service.CheckInService;
import jakarta.validation.Valid;
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

    /**
     * 查某一天的打卡状态；不传 `date` 按服务器当天（Asia/Shanghai）。
     *
     * <p>日期收字符串再走 {@link AppTime#parseDate}，**不交给 Spring 的 {@code @DateTimeFormat}**：
     * 那样同一个字段会有两套解析（请求体走 AppTime、查询参数走 Spring），
     * 于是 `2026-02-31` 这种「格式对但日子不存在」的输入在两条路径上给出不同的话术。
     * 现在两边都是同一句 40001。
     */
    @GetMapping
    public ApiResponse<CheckInDay> day(
            @PathVariable long petId,
            @RequestParam(required = false) String date) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(checkInService.dayState(userId, petId, parseOptionalDate(date)));
    }

    @PostMapping
    public ApiResponse<CheckInDay> submit(@PathVariable long petId,
                                         @Valid @RequestBody CheckInSubmitRequest request) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(checkInService.submit(userId, petId, request));
    }

    /** 撤销某一项（填错了）。日期口径同 {@link #day}。 */
    @DeleteMapping("/item")
    public ApiResponse<CheckInDay> undo(
            @PathVariable long petId,
            @RequestParam String date,
            @RequestParam int category) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(checkInService.undo(userId, petId, AppTime.parseDate(date), category));
    }

    /** 不传日期表示「用今天」，空串按没传处理（前端清空输入框后常发 `date=`）。 */
    private static LocalDate parseOptionalDate(String raw) {
        return raw == null || raw.isBlank() ? null : AppTime.parseDate(raw);
    }

    @GetMapping("/streak")
    public ApiResponse<CheckInStreak> streak(@PathVariable long petId) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(checkInService.streak(userId, petId));
    }
}
