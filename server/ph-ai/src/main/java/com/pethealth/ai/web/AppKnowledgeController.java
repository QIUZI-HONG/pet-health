package com.pethealth.ai.web;

import com.pethealth.ai.service.KnowledgeBrowseService;
import com.pethealth.api.app.KnowledgeEntryView;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.security.LoginDomain;
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
 * 知识库浏览（F024 / F025），契约见 contract/app.yaml 的 {@code /knowledge/**}。
 *
 * <p><b>需要登录</b>：与社区同属「内容面」（交付文档 2.2 的权限矩阵里游客只给
 * 「浏览服务 / 商家」（文档用词）那一格）；而且条目要展示复核状态，是「给人看的内容」
 * 而不是「找服务」。
 *
 * <p>只读：知识条目的维护在知识库那边（运营侧），C 端这一组没有写接口。
 */
@RestController
@RequestMapping("/api/v1/app/knowledge/entries")
@Validated
public class AppKnowledgeController {

    private final KnowledgeBrowseService browseService;

    public AppKnowledgeController(KnowledgeBrowseService browseService) {
        this.browseService = browseService;
    }

    /** 列表：按分类翻 / 按关键词搜。**列表不带正文**（点进去才拿）。 */
    @GetMapping
    public ApiResponse<PageResult<KnowledgeEntryView>> list(
            @RequestParam(name = "category_code", required = false)
            @Size(max = 32, message = "分类编码最长 32 个字符") String categoryCode,
            @RequestParam(required = false)
            @Size(max = 64, message = "关键词最长 64 个字符") String keyword,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(name = "page_size", defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = PageResult.MAX_PAGE_SIZE, message = "每页最多 " + PageResult.MAX_PAGE_SIZE + " 条")
            long pageSize) {
        CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(browseService.page(categoryCode, keyword, page, pageSize));
    }

    /** 详情：多正文与来源。不可读的编号回 40400。 */
    @GetMapping("/{code}")
    public ApiResponse<KnowledgeEntryView> detail(@PathVariable @Size(max = 32, message = "编号最长 32 个字符") String code) {
        CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(browseService.detail(code));
    }
}
