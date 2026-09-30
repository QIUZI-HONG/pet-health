package com.pethealth.content.web;

import com.pethealth.api.content.CommunityAnswerCreateRequest;
import com.pethealth.api.content.CommunityAnswerView;
import com.pethealth.api.content.CommunityQuestionCreateRequest;
import com.pethealth.api.content.CommunityQuestionView;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.security.LoginDomain;
import com.pethealth.content.service.QuestionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端的问答互助接口，契约见 contract/app.yaml 的 {@code /community/questions/**}
 * （切片 #84 / F020；决策见 ADR-0041 第一节）。
 *
 * <p>五个动作：列表、提问、详情（含回答）、回答、采纳。
 *
 * <p>提问与回答都过**发文权益**那道门（{@code community.post}）：回答也是发文，见
 * {@code PostingAccess} 的说明。采纳不过门——它是提问者对已有回答的选择，不是发文；
 * 但也**不是谁都能做**：只有提问者，别人一律 40400。
 */
@RestController
@RequestMapping("/api/v1/app/community/questions")
@Validated
public class AppCommunityQuestionController {

    private final QuestionService questionService;

    public AppCommunityQuestionController(QuestionService questionService) {
        this.questionService = questionService;
    }

    /** 问答列表（默认只给已发布；{@code mine=true} 给「我提的问题」，含待审与被拒）。 */
    @GetMapping
    public ApiResponse<PageResult<CommunityQuestionView>> list(
            @RequestParam(name = "disease_tag", required = false)
            @Size(max = 32, message = "慢病标签最长 32 个字符") String diseaseTag,
            @RequestParam(defaultValue = "false") boolean mine,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(name = "page_size", defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = PageResult.MAX_PAGE_SIZE, message = "每页最多 " + PageResult.MAX_PAGE_SIZE + " 条")
            long pageSize) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(questionService.list(userId, diseaseTag, mine, page, pageSize));
    }

    /** 提问（需要权益码 {@code community.post}：没有 → 40300）。 */
    @PostMapping
    public ApiResponse<CommunityQuestionView> create(
            @Valid @RequestBody CommunityQuestionCreateRequest request) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(questionService.create(userId, request));
    }

    /** 提问详情（含回答：已发布的 + 我自己写的）。 */
    @GetMapping("/{question_id}")
    public ApiResponse<CommunityQuestionView> get(@PathVariable("question_id") long questionId) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(questionService.get(userId, questionId));
    }

    /** 回答（需要权益码 {@code community.post}；只能回答已发布的提问）。 */
    @PostMapping("/{question_id}/answers")
    public ApiResponse<CommunityAnswerView> answer(@PathVariable("question_id") long questionId,
                                                   @Valid @RequestBody CommunityAnswerCreateRequest request) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(questionService.createAnswer(userId, questionId, request));
    }

    /** 采纳回答（只有提问者；一条提问只能采纳一个，重复 → 40900）。 */
    @PostMapping("/{question_id}/answers/{answer_id}/adopt")
    public ApiResponse<CommunityQuestionView> adopt(@PathVariable("question_id") long questionId,
                                                    @PathVariable("answer_id") long answerId) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(questionService.adopt(userId, questionId, answerId));
    }
}
