package com.pethealth.record.web;

import com.pethealth.api.app.EpidemicRecordRequest;
import com.pethealth.api.app.EpidemicRecordView;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.security.LoginDomain;
import com.pethealth.record.service.EpidemicRecordService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * C 端防疫记录接口，契约见 contract/app.yaml 的 {@code /pets/{pet_id}/epidemic-records}。
 *
 * <p>它同时是两件事的数据源：疫苗/驱虫提醒（ADR-0019）与健康评分的「防疫」维度。
 */
@RestController
@RequestMapping("/api/v1/app/pets/{petId}/epidemic-records")
public class EpidemicRecordController {

    private final EpidemicRecordService epidemicRecordService;

    public EpidemicRecordController(EpidemicRecordService epidemicRecordService) {
        this.epidemicRecordService = epidemicRecordService;
    }

    @GetMapping
    public ApiResponse<List<EpidemicRecordView>> list(@PathVariable long petId) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(epidemicRecordService.list(userId, petId));
    }

    @PostMapping
    public ApiResponse<EpidemicRecordView> create(@PathVariable long petId,
                                                 @Valid @RequestBody EpidemicRecordRequest input) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(epidemicRecordService.create(userId, petId, input));
    }

    @DeleteMapping("/{recordId}")
    public ApiResponse<Void> delete(@PathVariable long petId, @PathVariable long recordId) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        epidemicRecordService.delete(userId, petId, recordId);
        return ApiResponse.ok();
    }
}
