package com.pethealth.content.web;

import com.pethealth.api.content.CommunityCardCreateRequest;
import com.pethealth.api.content.CommunityCardView;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.security.LoginDomain;
import com.pethealth.content.service.CardService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端的经验卡片接口，契约见 contract/app.yaml 的 {@code /community/cards/**}
 * （切片 #84 / F020；决策见 ADR-0041 第一节 / ADR-0050 第一节）。
 *
 * <p>四个动作：列表（按同品种 / 同病聚合）、一键生成（发文要权益码 {@code community.post}）、
 * 详情、点赞与取消点赞。
 *
 * <p>**嵌在首页与档案页的侧栏，不做独立 Tab**（ADR-0041 第一节）：所以这里的列表刻意做成
 * 「按标签筛选的一个列表」，而不是「社区首页需要的一整套导航」。
 *
 * <p>别人的待审内容一律 40400（越权与不存在同码，docs/conventions.md）：
 * 用 id 探测别人内容的存在性，不该得到「存在但无权」这种有信息量的答复。
 */
@RestController
@RequestMapping("/api/v1/app/community/cards")
@Validated
public class AppCommunityCardController {

    private final CardService cardService;

    public AppCommunityCardController(CardService cardService) {
        this.cardService = cardService;
    }

    /** 卡片列表（默认只给已发布；{@code mine=true} 给「我的卡片」，含待审与被拒）。 */
    @GetMapping
    public ApiResponse<PageResult<CommunityCardView>> list(
            @RequestParam(required = false)
            @Min(value = 1, message = "物种只能是 1 犬 / 2 猫")
            @Max(value = 2, message = "物种只能是 1 犬 / 2 猫") Integer species,
            @RequestParam(required = false)
            @Size(max = 32, message = "品种最长 32 个字符") String breed,
            @RequestParam(name = "disease_tag", required = false)
            @Size(max = 32, message = "慢病标签最长 32 个字符") String diseaseTag,
            @RequestParam(defaultValue = "false") boolean mine,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(name = "page_size", defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = PageResult.MAX_PAGE_SIZE, message = "每页最多 " + PageResult.MAX_PAGE_SIZE + " 条")
            long pageSize) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(cardService.list(userId, species, breed, diseaseTag, mine, page, pageSize));
    }

    /** 一键生成匿名卡片（需要权益码 {@code community.post}：没有 → 40300）。 */
    @PostMapping
    public ApiResponse<CommunityCardView> create(@Valid @RequestBody CommunityCardCreateRequest request) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(cardService.create(userId, request));
    }

    /** 卡片详情（待审 / 被拒的只有作者看得到）。 */
    @GetMapping("/{card_id}")
    public ApiResponse<CommunityCardView> get(@PathVariable("card_id") long cardId) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(cardService.get(userId, cardId));
    }

    /** 点赞（幂等；不需要发文权益——它不是发文）。 */
    @PostMapping("/{card_id}/likes")
    public ApiResponse<CommunityCardView> like(@PathVariable("card_id") long cardId) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(cardService.like(userId, cardId));
    }

    /** 取消点赞（子资源删除幂等：本来就没点过也返回成功）。 */
    @DeleteMapping("/{card_id}/likes")
    public ApiResponse<CommunityCardView> unlike(@PathVariable("card_id") long cardId) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(cardService.unlike(userId, cardId));
    }
}
