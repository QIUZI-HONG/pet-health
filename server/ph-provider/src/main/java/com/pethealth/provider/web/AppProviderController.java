package com.pethealth.provider.web;

import com.pethealth.api.app.ProviderDetailView;
import com.pethealth.api.app.ProviderSummaryView;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.api.PageResult;
import com.pethealth.provider.service.ProviderBrowseService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端的服务者浏览（找店 / 看店），契约见 contract/app.yaml 的 {@code /providers}。
 *
 * <p><b>这两条是这个项目里唯一不需要身份的只读接口</b>（ADR-0037 第一节：游客不是角色，
 * 只读接口不需要身份）。因此本类里没有一处 {@code CurrentUser.requireDomain(...)}——
 * 那是「本端身份」的声明，而这两条接口对身份没有要求：未登录能看，带令牌结果也一样。
 * 免登录是在 {@code JwtAuthenticationFilter} 的免登录表里放行的，**不是**在这里少写一句。
 *
 * <p>参数校验（分类 1–6、关键词 ≤32、页码与每页条数）来自契约的 400 描述，
 * 靠类上的 {@code @Validated} 生效——没有它，{@code @Min}/{@code @Max} 只是装饰。
 */
@RestController
@RequestMapping("/api/v1/app/providers")
@Validated
public class AppProviderController {

    private final ProviderBrowseService browseService;

    public AppProviderController(ProviderBrowseService browseService) {
        this.browseService = browseService;
    }

    /** 找店：分类 / 关键词 / 分页。只列**可下单**的店（口径见 service）。 */
    @GetMapping
    public ApiResponse<PageResult<ProviderSummaryView>> list(
            @RequestParam(required = false)
            @Min(value = 1, message = "服务者分类只能是 1（医院）–6（间接服务）")
            @Max(value = 6, message = "服务者分类只能是 1（医院）–6（间接服务）") Integer type,
            @RequestParam(required = false)
            @Size(max = 32, message = "关键词最长 32 个字符") String keyword,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(name = "page_size", defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = PageResult.MAX_PAGE_SIZE, message = "每页最多 " + PageResult.MAX_PAGE_SIZE + " 条")
            long pageSize) {
        return ApiResponse.ok(browseService.browse(type, keyword, page, pageSize));
    }

    /** 看店：资质摘要 + 营业时间 + **在架服务项与价格**。不可浏览一律 40400。 */
    @GetMapping("/{provider_id}")
    public ApiResponse<ProviderDetailView> detail(@PathVariable("provider_id") long providerId) {
        return ApiResponse.ok(browseService.view(providerId));
    }
}
