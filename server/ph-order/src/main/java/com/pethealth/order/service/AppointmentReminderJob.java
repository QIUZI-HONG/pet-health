package com.pethealth.order.service;

import com.pethealth.common.time.AppTime;
import com.pethealth.order.config.OrderProperties;
import com.pethealth.order.domain.ServiceOrder;
import com.pethealth.order.mapper.ServiceOrderMapper;
import com.pethealth.api.reminder.BusinessMessageApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * 预约提醒（F026）：在预约开始前若干小时给用户发一条站内消息。
 *
 * <p><b>为什么是本模块发</b>：预约是 {@code ph-order} 的事实，而 {@code ph-reminder} 管的是
 * 健康提醒（它自己按规则算）。跨模块发业务通知的口子已经有了（{@code BusinessMessageApi}），
 * 事件发生方直接调——把「扫未来 N 小时的预约」这个查询放进 ph-reminder，等于让它去读别人的表。
 *
 * <p><b>为什么每小时跑而不是每天跑</b>：提醒的语义是「快到了」（默认 2 小时），按天跑会让
 * 「今天下午 3 点」的单在凌晨 1 点就收到提醒。每小时跑一次，窗口内命中一次即发；
 * 去重键 {@code order-appointment-{订单 id}} 保证同一条预约只收到一条
 * ——哪怕它在窗口里被扫到多次（2 小时窗口 + 每小时一轮，必然扫到两次以上）。
 *
 * <p><b>号源时段是「日期 + 钟点」两个字段</b>（{@code appointment_date} + {@code start_time}），
 * 不是单一时间戳，所以窗口判断在这里拼起来做。钟点解析失败**不静默**：
 * 那是脏数据，运维该知道哪一单因此没被提醒（同 {@code SlotGrid} 的取舍）。
 *
 * <p><b>触发在 {@link AppointmentReminderScheduler}</b>：{@code @Scheduled} 方法调本类的
 * {@link #remind()} 是自调用，Spring 的事务代理不生效；而测试注入 Bean 调用时是有事务的。
 * 分居两个 bean 才能让定时触发与测试走同一条路径。
 */
@Component
public class AppointmentReminderJob {

    private static final Logger log = LoggerFactory.getLogger(AppointmentReminderJob.class);

    /** 与下单时的校验同一格式（{@code OrderCreateRequest} 的 start_time）。 */
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final ServiceOrderMapper orderMapper;
    private final BusinessMessageApi businessMessages;
    private final ProviderFacts providerFacts;
    private final OrderProperties properties;

    public AppointmentReminderJob(ServiceOrderMapper orderMapper, BusinessMessageApi businessMessages,
                                  ProviderFacts providerFacts, OrderProperties properties) {
        this.orderMapper = orderMapper;
        this.businessMessages = businessMessages;
        this.providerFacts = providerFacts;
        this.properties = properties;
    }

    /**
     * 扫一轮并发出提醒，返回发出的条数。
     *
     * <p>抽出来是为了能被测试直接调用——批算没有 HTTP 入口，「只测 HTTP 缝」（ADR-0014）
     * 在这一处无法成立，理由与本仓其它批算（券过期、资质到期、月度考核）相同。
     */
    @Transactional
    public int remind() {
        LocalDateTime now = AppTime.now();
        LocalDateTime deadline = now.plusHours(properties.appointmentReminderHours());
        LocalDate today = now.toLocalDate();
        // 只圈今天与明天：窗口最多几小时，跨不到后天去；多圈一天只是多读几行
        List<ServiceOrder> candidates = orderMapper.findAwaitingVisitBetween(today, today.plusDays(1));

        int sent = 0;
        for (ServiceOrder order : candidates) {
            LocalDateTime startAt = startAtOf(order);
            if (startAt == null || startAt.isBefore(now) || startAt.isAfter(deadline)) {
                continue;
            }
            businessMessages.notify(new BusinessMessageApi.BusinessNotification(
                    order.getUserId(),
                    BusinessMessageApi.TYPE_ORDER,
                    "order-appointment-" + order.getId(),
                    "预约快到了",
                    text(order, startAt),
                    "查看订单",
                    "/orders/" + order.getId()));
            sent++;
        }
        return sent;
    }

    /** 提醒正文：带上「哪个项目、几点、哪家店」——用户要凭这三样决定出不出门。 */
    private String text(ServiceOrder order, LocalDateTime startAt) {
        Map<Long, String> names = providerFacts.providerNames(List.of(order.getProviderId()));
        String provider = names.getOrDefault(order.getProviderId(), "门店");
        return "「" + order.getServiceName() + "」预约在今天 " + startAt.toLocalTime().format(HH_MM)
                + "（" + provider + "）。请按预约时间到店；如需改期请联系门店。";
    }

    /** 把「日期 + 钟点」拼成一个时刻；钟点脏数据返回 null 并留痕。 */
    private LocalDateTime startAtOf(ServiceOrder order) {
        if (order.getAppointmentDate() == null || order.getStartTime() == null) {
            return null;
        }
        try {
            return LocalDateTime.of(order.getAppointmentDate(), LocalTime.parse(order.getStartTime(), HH_MM));
        } catch (DateTimeParseException e) {
            log.warn("预约时间无法解析，本轮跳过提醒：orderId={} startTime={}",
                    order.getId(), order.getStartTime());
            return null;
        }
    }
}
