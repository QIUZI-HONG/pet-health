package com.pethealth.content.web;

import com.pethealth.api.admin.ReviewRejectRequest;
import com.pethealth.api.content.ContentReviewItemView;
import com.pethealth.api.content.SensitiveWordRequest;
import com.pethealth.api.content.SensitiveWordView;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.security.LoginDomain;
import com.pethealth.content.domain.ContentType;
import com.pethealth.content.service.ContentReviewService;
import com.pethealth.content.service.SensitiveWordService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营后台的内容审核接口，契约见 contract/admin.yaml 的 {@code /community/**}
 * （切片 #84 / F020；权限见 ADR-0037 第一节「内容审核归运营」）。
 *
 * <p>六个动作：审核队列、通过、驳回 / 下架、词表列表、加词、改词。
 *
 * <p>三个口径：
 *
 * <ul>
 *   <li><b>审核队列按类型分别看</b>（{@code content_type} 必填）：三种内容各住一张表，
 *       合并成一条跨表 UNION 只会把「总条数」变成三次 COUNT 的和，换来的只是省一次点击；
 *   <li><b>通过不带请求体</b>：通过时没有要对作者说的话（内容只是出现了），
 *       硬塞一个「备注」字段而无处可存，就是让调用方以为它会被记录；
 *   <li><b>驳回 / 下架合并成一个入口</b>：它们是同一个状态迁移（{@code → 已驳回}），
 *       对用户的效果一样（不再出现），理由必填且会展示给作者自己。
 * </ul>
 *
 * <p>运营与超管在这一层**还没有区分**（后台角色体系未落地，见 admin.yaml 的说明）：
 * 本切片对所有 admin 域身份一视同仁，等角色体系落地后收紧。
 */
@RestController
@RequestMapping("/api/v1/admin/community")
@Validated
public class AdminContentController {

    private final ContentReviewService reviewService;
    private final SensitiveWordService sensitiveWordService;

    public AdminContentController(ContentReviewService reviewService, SensitiveWordService sensitiveWordService) {
        this.reviewService = reviewService;
        this.sensitiveWordService = sensitiveWordService;
    }

    // ---------------------------------------------------------------- 审核队列与处置

    /** 审核队列（按类型 + 状态 + 可选作者，先到先审）。 */
    @GetMapping("/contents")
    public ApiResponse<PageResult<ContentReviewItemView>> contents(
            @RequestParam(name = "content_type")
            @Min(value = 1, message = "内容类型只能是 1 卡片 / 2 提问 / 3 回答")
            @Max(value = 3, message = "内容类型只能是 1 卡片 / 2 提问 / 3 回答") int contentType,
            @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "状态只能是 0 待审 / 1 已发布 / 2 已驳回")
            @Max(value = 2, message = "状态只能是 0 待审 / 1 已发布 / 2 已驳回") int status,
            @RequestParam(name = "author_id", required = false) Long authorId,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(name = "page_size", defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = PageResult.MAX_PAGE_SIZE, message = "每页最多 " + PageResult.MAX_PAGE_SIZE + " 条")
            long pageSize) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(reviewService.queue(ContentType.of(contentType), status, authorId, page, pageSize));
    }

    /** 通过（待审 / 被机审驳回 → 已发布）；已经是已发布 → 40900。 */
    @PostMapping("/contents/{content_type}/{content_id}/approve")
    public ApiResponse<ContentReviewItemView> approve(@PathVariable("content_type") int contentType,
                                                      @PathVariable("content_id") long contentId) {
        long operatorId = CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(reviewService.approve(operatorId, ContentType.of(contentType), contentId));
    }

    /** 驳回 / 下架（理由必填，会展示给作者自己）；已经是驳回状态 → 40900。 */
    @PostMapping("/contents/{content_type}/{content_id}/reject")
    public ApiResponse<ContentReviewItemView> reject(@PathVariable("content_type") int contentType,
                                                     @PathVariable("content_id") long contentId,
                                                     @Valid @RequestBody ReviewRejectRequest request) {
        long operatorId = CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(reviewService.reject(operatorId, ContentType.of(contentType), contentId,
                request.reason().trim()));
    }

    // ---------------------------------------------------------------- 敏感词表

    /** 词表分页（含停用：运营要能看见自己停用过的词）。 */
    @GetMapping("/sensitive-words")
    public ApiResponse<PageResult<SensitiveWordView>> words(
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false)
            @Size(max = 64, message = "关键词最长 64 个字符") String keyword,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(name = "page_size", defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = PageResult.MAX_PAGE_SIZE, message = "每页最多 " + PageResult.MAX_PAGE_SIZE + " 条")
            long pageSize) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(sensitiveWordService.list(enabled, keyword, page, pageSize));
    }

    /** 新增敏感词（同一句词重复 → 40900）。 */
    @PostMapping("/sensitive-words")
    public ApiResponse<SensitiveWordView> createWord(@Valid @RequestBody SensitiveWordRequest request) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(sensitiveWordService.create(request));
    }

    /** 修改敏感词（改词 / 启停 / 说明）；没有删除动作，停用即「不再拦」。 */
    @PutMapping("/sensitive-words/{word_id}")
    public ApiResponse<SensitiveWordView> updateWord(@PathVariable("word_id") long wordId,
                                                     @Valid @RequestBody SensitiveWordRequest request) {
        CurrentUser.requireDomain(LoginDomain.ADMIN);
        return ApiResponse.ok(sensitiveWordService.update(wordId, request));
    }
}
