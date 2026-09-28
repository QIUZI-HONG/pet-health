package com.pethealth.reminder.web;

import com.pethealth.api.app.MessageView;
import com.pethealth.api.app.ReminderSettingRequest;
import com.pethealth.api.app.ReminderSettingView;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.security.CurrentUser;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import com.pethealth.common.security.LoginDomain;
import com.pethealth.reminder.service.MessageService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * C 端消息中心接口，契约见 contract/app.yaml 的 {@code /messages}。
 *
 * <p>路径统一放在 {@code /messages} 而不是挂在某个宠物下：**消息是账号级的**——
 * 业务通知（订单/券）不属于任何一只宠物，健康提醒虽然指向宠物但用户是按「我的消息」来看的。
 */
@RestController
@RequestMapping("/api/v1/app/messages")
// @Validated：方法参数上的 @Min/@Max 要靠它才会生效（Spring 的 MethodValidationPostProcessor）
@Validated
public class MessageController {

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    /**
     * 消息列表。
     *
     * <p>`page` / `page_size` 带下界校验：契约（`common.yaml`）写的是 `minimum: 1`，
     * 而 `page=0` 会被原样回显、`page_size=0` 会拼出 `LIMIT 0` 返回空列表——
     * 两种都是「看起来正常但答案是错的」，比报错更难发现（测试报告 D20）。
     */
    @GetMapping
    public ApiResponse<PageResult<MessageView>> list(
            @RequestParam(required = false) Integer kind,
            @RequestParam(name = "unread_only", defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(name = "page_size", defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条") @Max(value = 100, message = "每页最多 100 条") long pageSize) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(messageService.list(userId, kind, unreadOnly, page, pageSize));
    }

    @GetMapping("/highlights")
    public ApiResponse<List<MessageView>> highlights(@RequestParam(defaultValue = "6") int limit) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(messageService.highlights(userId, limit));
    }

    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Integer>> unreadCount() {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(messageService.unreadCount(userId));
    }

    @PutMapping("/read-all")
    public ApiResponse<Map<String, Integer>> readAll() {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(messageService.markAllRead(userId));
    }

    @DeleteMapping("/{messageId}")
    public ApiResponse<Void> delete(@PathVariable long messageId) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        messageService.delete(userId, messageId);
        return ApiResponse.ok();
    }

    @PutMapping("/{messageId}/read")
    public ApiResponse<MessageView> markRead(@PathVariable long messageId) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(messageService.markRead(userId, messageId));
    }

    @GetMapping("/settings")
    public ApiResponse<List<ReminderSettingView>> settings() {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(messageService.settings(userId));
    }

    @PutMapping("/settings")
    public ApiResponse<List<ReminderSettingView>> updateSetting(@Valid @RequestBody ReminderSettingRequest request) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(messageService.updateSetting(userId, request));
    }
}
