package com.pethealth.provider.web;

import com.pethealth.api.provider.BusinessHoursRequest;
import com.pethealth.api.provider.ProviderProfileRequest;
import com.pethealth.api.provider.ProviderProfileView;
import com.pethealth.api.provider.ProviderQualificationsRequest;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.security.LoginDomain;
import com.pethealth.provider.service.ProviderProfileService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务者后台的门店接口，契约见 contract/provider.yaml 的 {@code /profile}。
 *
 * <p>门禁按状态分两种结果，**不要合成一个**：
 *
 * <ul>
 *   <li><b>还没通过审核</b>（待审核 / 驳回）：门店这个资源还不存在——读 40400，
 *       写 40300 且说明是「审核中」还是「已被驳回」。审核进度与门店快照走
 *       {@code /onboarding/applications}（申请详情里带着 provider）；
 *   <li><b>已通过但被冻结 / 资质过期</b>：门店读得到（同一份数据，界面要展示状态），
 *       写被 40300 拦住。
 * </ul>
 *
 * <p>这一条是交付文档 #104 的验收项：「审核通过后可维护门店信息与营业时间」。
 */
@RestController
@RequestMapping("/api/v1/provider/profile")
public class ProviderProfileController {

    private final ProviderProfileService profileService;

    public ProviderProfileController(ProviderProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    public ApiResponse<ProviderProfileView> get() {
        CurrentUser.requireDomain(LoginDomain.PROVIDER);
        return ApiResponse.ok(profileService.get());
    }

    @PutMapping
    public ApiResponse<ProviderProfileView> update(@Valid @RequestBody ProviderProfileRequest request) {
        CurrentUser.requireDomain(LoginDomain.PROVIDER);
        return ApiResponse.ok(profileService.update(request));
    }

    /** 整体替换营业时间；空数组表示整周休息。 */
    @PutMapping("/business-hours")
    public ApiResponse<ProviderProfileView> updateBusinessHours(@Valid @RequestBody BusinessHoursRequest request) {
        CurrentUser.requireDomain(LoginDomain.PROVIDER);
        return ApiResponse.ok(profileService.updateBusinessHours(request));
    }

    /**
     * 补交 / 更新资质材料（整体替换，新材料回到待审核）。
     *
     * <p>存在的意义是「资质过期后被自动下架，补交材料即可恢复上架」这条链路的入口
     * （2026-09-29 口径）。
     */
    @PutMapping("/qualifications")
    public ApiResponse<ProviderProfileView> updateQualifications(
            @Valid @RequestBody ProviderQualificationsRequest request) {
        CurrentUser.requireDomain(LoginDomain.PROVIDER);
        return ApiResponse.ok(profileService.resubmitQualifications(request.qualifications()));
    }
}
