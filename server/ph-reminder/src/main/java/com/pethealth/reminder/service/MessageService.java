package com.pethealth.reminder.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pethealth.api.app.MessageView;
import com.pethealth.api.app.ReminderSettingRequest;
import com.pethealth.api.app.ReminderSettingView;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.time.AppTime;
import com.pethealth.reminder.domain.Message;
import com.pethealth.reminder.domain.ReminderSetting;
import com.pethealth.reminder.mapper.MessageMapper;
import com.pethealth.reminder.mapper.ReminderSettingMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 消息中心：列表、未读数、已读、按类型开关（切片 #99，决策见 ADR-0019）。
 *
 * <p>三条贯穿本类的规则：
 *
 * <ol>
 *   <li><b>读取时惰性补算提醒</b>（{@link #materialize}）：只做站内时，「批算」与「用户看到时才算」
 *       对用户不可区分，但惰性补算保证打开时看到的一定是最新的。它和每日批算共用同一个幂等生成函数。
 *   <li><b>红色提醒不可关闭</b>：用户安全优先于体验（交付文档 13.1 的决策优先级）。
 *   <li><b>已读写入幂等</b>：重复标记已读不改时间（多端同步时第一条为准，[#85] 会在此基础上做同步）。
 * </ol>
 */
@Service
public class MessageService {

    /** 提醒类型 → 展示名。业务通知的类型不在这里（它们不是「提醒」）。 */
    private static final Map<Integer, String> REMINDER_TYPE_NAMES = new LinkedHashMap<>();

    static {
        REMINDER_TYPE_NAMES.put(Message.TYPE_VACCINE, "疫苗到期");
        REMINDER_TYPE_NAMES.put(Message.TYPE_DEWORM, "驱虫到期");
        REMINDER_TYPE_NAMES.put(Message.TYPE_DAILY, "每日打卡");
        REMINDER_TYPE_NAMES.put(Message.TYPE_ABNORMAL, "健康异常");
        REMINDER_TYPE_NAMES.put(Message.TYPE_TREND, "指标趋势");
        REMINDER_TYPE_NAMES.put(Message.TYPE_CHRONIC_ELDERLY, "慢病与老年照护");
    }

    private final MessageMapper messageMapper;
    private final ReminderSettingMapper settingMapper;
    private final ReminderRuleService ruleService;
    private final ReminderGenerator reminderGenerator;

    public MessageService(MessageMapper messageMapper,
                          ReminderSettingMapper settingMapper,
                          ReminderRuleService ruleService,
                          ReminderGenerator reminderGenerator) {
        this.messageMapper = messageMapper;
        this.settingMapper = settingMapper;
        this.ruleService = ruleService;
        this.reminderGenerator = reminderGenerator;
    }

    /** 消息列表（分页）。读取前先补算一次提醒。 */
    @Transactional
    public PageResult<MessageView> list(long userId, Integer kind, boolean unreadOnly, long page, long pageSize) {
        reminderGenerator.materialize(userId);
        IPage<Message> result = messageMapper.selectPage(new Page<>(page, pageSize),
                Wrappers.<Message>lambdaQuery()
                        .eq(Message::getUserId, userId)
                        .eq(kind != null, Message::getKind, kind)
                        .eq(unreadOnly, Message::getStatus, Message.STATUS_SENT)
                        .orderByDesc(Message::getCreatedAt)
                        .orderByDesc(Message::getId));
        return PageResult.from(result, MessageService::toView);
    }

    /**
     * 首页强提醒流：未读的健康提醒，按「风险等级 → 指向时间」排序取前 N 条。
     *
     * <p>只取健康提醒——业务通知（订单/券）不该占据「需要你关注」这个位置。
     */
    @Transactional
    public List<MessageView> highlights(long userId, int limit) {
        reminderGenerator.materialize(userId);
        List<Message> messages = messageMapper.selectList(Wrappers.<Message>lambdaQuery()
                .eq(Message::getUserId, userId)
                .eq(Message::getKind, Message.KIND_REMINDER)
                .eq(Message::getStatus, Message.STATUS_SENT)
                .orderByDesc(Message::getRiskLevel)
                .orderByAsc(Message::getRemindAt)
                .last("LIMIT " + Math.max(1, Math.min(limit, 10))));
        return messages.stream().map(MessageService::toView).toList();
    }

    /**
     * 未读数。
     *
     * <p>**这里不做惰性补算**：角标会随着每次页面切换被拉一次，让它写库不合适。
     * 补算留给真正展示内容的两个入口（消息列表、首页强提醒流），角标在它们之后由前端刷新。
     */
    public Map<String, Integer> unreadCount(long userId) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("unread", messageMapper.countUnread(userId));
        counts.put("unread_reminders", messageMapper.countUnreadReminders(userId));
        return counts;
    }

    /** 标记单条已读。幂等：重复标记不改 read_at（多端同步时第一条为准）。 */
    @Transactional
    public MessageView markRead(long userId, long messageId) {
        Message message = messageMapper.selectOne(Wrappers.<Message>lambdaQuery()
                .eq(Message::getId, messageId)
                .eq(Message::getUserId, userId));
        if (message == null) {
            // 别人的消息与不存在都按 404 处理，不泄露 id 是否存在
            throw BusinessException.notFound();
        }
        if (!message.isRead()) {
            message.setStatus(Message.STATUS_READ);
            message.setReadAt(AppTime.now());
            messageMapper.updateById(message);
        }
        return toView(message);
    }

    /**
     * 删除单条消息（软删除，幂等）。
     *
     * <p>删除 ≠ 关闭该类型：用户删掉这一条，不代表以后不想收这类提醒（ADR-0019）。
     * 要停止某一类请用「提醒设置」的开关。
     */
    @Transactional
    public void delete(long userId, long messageId) {
        Message message = messageMapper.selectOne(Wrappers.<Message>lambdaQuery()
                .eq(Message::getId, messageId)
                .eq(Message::getUserId, userId));
        if (message == null) {
            // 别人的消息与不存在都按 404，不泄露 id 是否存在
            throw BusinessException.notFound();
        }
        message.setStatus(Message.STATUS_CANCELLED);
        messageMapper.updateById(message);
        messageMapper.deleteById(messageId);
    }

    /**
     * 全部已读。幂等：没有未读时返回成功。
     *
     * <p>**先补算再标记**：否则用户点「全部已读」之后，本次读取才补算出来的那条提醒仍是未读，
     * 角标看起来像没生效（踩过一次）。
     */
    @Transactional
    public Map<String, Integer> markAllRead(long userId) {
        reminderGenerator.materialize(userId);
        List<Message> unread = messageMapper.selectList(Wrappers.<Message>lambdaQuery()
                .eq(Message::getUserId, userId)
                .eq(Message::getStatus, Message.STATUS_SENT));
        LocalDateTime now = AppTime.now();
        for (Message message : unread) {
            message.setStatus(Message.STATUS_READ);
            message.setReadAt(now);
            messageMapper.updateById(message);
        }
        return unreadCount(userId);
    }

    // ---------------------------------------------------------------- 开关

    /** 我的提醒开关：未设置过的按默认开启返回，前端不必自己补默认值。 */
    @Transactional(readOnly = true)
    public List<ReminderSettingView> settings(long userId) {
        Map<Integer, ReminderSetting> stored = new LinkedHashMap<>();
        for (ReminderSetting setting : settingMapper.selectList(Wrappers.<ReminderSetting>lambdaQuery()
                .eq(ReminderSetting::getUserId, userId))) {
            stored.put(setting.getType(), setting);
        }
        List<ReminderSettingView> views = new ArrayList<>();
        for (Map.Entry<Integer, String> entry : REMINDER_TYPE_NAMES.entrySet()) {
            int type = entry.getKey();
            ReminderSetting setting = stored.get(type);
            views.add(new ReminderSettingView(
                    type,
                    entry.getValue(),
                    setting == null || setting.isOn(),
                    ruleService.platformEnabled(type),
                    isClosable(type)));
        }
        return views;
    }

    /** 修改某类提醒的开关。 */
    @Transactional
    public List<ReminderSettingView> updateSetting(long userId, ReminderSettingRequest request) {
        int type = request.type();
        if (!REMINDER_TYPE_NAMES.containsKey(type)) {
            throw BusinessException.paramInvalid("这个提醒类型不能开关");
        }
        if (!request.enabled() && !isClosable(type)) {
            // 红色等级的健康提醒不允许关闭：用户安全优先于体验（ADR-0019）
            throw BusinessException.paramInvalid("这类提醒关系安全，不能关闭");
        }
        if (request.enabled() && !ruleService.platformEnabled(type)) {
            throw BusinessException.paramInvalid("平台已暂停这类提醒，暂时无法开启");
        }

        ReminderSetting setting = settingMapper.selectOne(Wrappers.<ReminderSetting>lambdaQuery()
                .eq(ReminderSetting::getUserId, userId)
                .eq(ReminderSetting::getType, type));
        if (setting == null) {
            setting = new ReminderSetting();
            setting.setUserId(userId);
            setting.setType(type);
            setting.setEnabled(request.enabled() ? 1 : 0);
            settingMapper.insert(setting);
        } else {
            setting.setEnabled(request.enabled() ? 1 : 0);
            settingMapper.updateById(setting);
        }
        return settings(userId);
    }

    /**
     * 该类型是否允许用户关闭。
     *
     * <p>「红色等级的健康提醒不可关闭」（ADR-0019：用户安全 > 体验）在开关这一层的落地方式是
     * **按类型判定**——因为开关是类型粒度的，而一个类型里可能既有黄也有红。
     * 承载红色风险的是**异常类**（AI 咨询给出红色分级后会写进这一类，见 #98），
     * 所以它不可关闭；其余类型可关。
     */
    private boolean isClosable(int type) {
        return type != Message.TYPE_ABNORMAL;
    }

    private static MessageView toView(Message message) {
        return new MessageView(
                message.getId(),
                message.getKind(),
                message.getType(),
                message.getTitle(),
                message.getContent(),
                message.getRiskLevel(),
                message.getRemindAt(),
                message.isRead(),
                message.getReadAt(),
                message.getActionHint(),
                message.getActionTarget(),
                message.getPetId(),
                message.getCreatedAt());
    }
}
