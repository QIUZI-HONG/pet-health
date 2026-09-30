package com.pethealth.record.web;

import com.pethealth.api.app.ArchiveRecordRequest;
import com.pethealth.api.app.ArchiveRecordView;
import com.pethealth.api.app.ArchiveSectionView;
import com.pethealth.api.app.TimelineEventView;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.security.LoginDomain;
import com.pethealth.common.time.AppTime;
import com.pethealth.record.service.ArchiveSectionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * C 端档案分项、分项记录与时间轴接口，契约见 contract/app.yaml 的
 * {@code /pets/{pet_id}/archive-sections}、{@code /archive-records}、{@code /timeline}（切片 #102）。
 *
 * <p>路径拆成两个前缀而不是一个：**分项是入口**（8 个固定的展示单元），**记录是资源**
 * （可增删改的行）。把它们塞进同一条路径会让「POST /archive-sections」读起来像在创建一个分项。
 *
 * <p>时间轴刻意放在宠物下（{@code /pets/{id}/timeline}）而不是账号下：它讲的是**这只宠物**的事，
 * 多宠家庭切换宠物时应该整块换掉（与消息中心相反——那里是账号级的）。
 */
@RestController
@RequestMapping("/api/v1/app/pets/{petId}")
// @Validated：方法参数上的 @Min/@Max 要靠它生效（与 MessageController 同一处理）
@Validated
public class ArchiveSectionController {

    private final ArchiveSectionService archiveSectionService;

    public ArchiveSectionController(ArchiveSectionService archiveSectionService) {
        this.archiveSectionService = archiveSectionService;
    }

    @GetMapping("/archive-sections")
    public ApiResponse<List<ArchiveSectionView>> sections(@PathVariable long petId) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(archiveSectionService.sections(userId, petId));
    }

    /**
     * 分项记录列表。
     *
     * <p>日期收字符串再走 {@link AppTime#parseDate}，**不交给 Spring 的 {@code @DateTimeFormat}**：
     * 那样同一个字段会有两套解析（请求体走 AppTime、查询参数走 Spring），
     * `2026-02-31` 这种「格式对但日子不存在」的输入会在两条路径上给出不同的话术（打卡接口同）。
     */
    @GetMapping("/archive-records")
    public ApiResponse<PageResult<ArchiveRecordView>> records(
            @PathVariable long petId,
            @RequestParam(required = false) String section,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(name = "page_size", defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = PageResult.MAX_PAGE_SIZE, message = "每页最多 " + PageResult.MAX_PAGE_SIZE + " 条")
            long pageSize) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(archiveSectionService.records(userId, petId, section,
                parseDate(from), parseDate(to), page, pageSize));
    }

    @PostMapping("/archive-records")
    public ApiResponse<ArchiveRecordView> create(@PathVariable long petId,
                                                @Valid @RequestBody ArchiveRecordRequest request) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(archiveSectionService.create(userId, petId, request));
    }

    @PutMapping("/archive-records/{recordId}")
    public ApiResponse<ArchiveRecordView> update(@PathVariable long petId,
                                                @PathVariable long recordId,
                                                @Valid @RequestBody ArchiveRecordRequest request) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(archiveSectionService.update(userId, petId, recordId, request));
    }

    @DeleteMapping("/archive-records/{recordId}")
    public ApiResponse<Void> delete(@PathVariable long petId, @PathVariable long recordId) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        archiveSectionService.delete(userId, petId, recordId);
        return ApiResponse.ok();
    }

    @GetMapping("/timeline")
    public ApiResponse<PageResult<TimelineEventView>> timeline(
            @PathVariable long petId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(name = "page_size", defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = PageResult.MAX_PAGE_SIZE, message = "每页最多 " + PageResult.MAX_PAGE_SIZE + " 条")
            long pageSize) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(archiveSectionService.timeline(userId, petId, type,
                parseDate(from), parseDate(to), page, pageSize));
    }

    /** 空串按没传处理（前端清空输入框后常发 `from=`）。 */
    private static LocalDate parseDate(String raw) {
        return raw == null || raw.isBlank() ? null : AppTime.parseDate(raw);
    }
}
