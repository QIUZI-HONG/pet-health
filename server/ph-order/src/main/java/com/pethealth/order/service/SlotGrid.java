package com.pethealth.order.service;

import com.pethealth.api.provider.BusinessHour;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 时段网格：把「某天营业 09:00–18:00」摊成一个个可预约的时段。
 *
 * <p><b>粒度是 30 分钟一格，锚点取营业开始时间</b>（09:00–18:00 → 09:00、09:30 … 17:30，
 * 每格 30 分钟）。粒度与「每格默认容量 1」由项目所有者拍板（ADR-0049 §六）：
 * 服务者在营业时间内按格开放，「已满」也以格为单位判；「某格容量 &gt; 1」留作服务者可配项，
 * 本期不做。本类刻意是**纯计算**（只依赖营业时间），将来接「服务者可配」时只改这里。
 *
 * <p>三条边界口径：
 *
 * <ul>
 *   <li>末尾**不留半格**：{@code start + 30min > close} 就不切（09:00–17:20 的最后一格是 16:30–17:00）；
 *   <li>锚点不在整点 / 半点时（09:10 开始）网格跟着锚点走：09:10、09:40 …，不四舍五入——
 *       把门店自己填的营业时间改了才是真的错；
 *   <li>跨天营业（22:00–02:00）**不支持**：切不出来就返回空（{@code BusinessHour} 的
 *       类注释已经写明本期不支持跨天）。这里不猜、不循环，免得把 22:00–02:00 摊成
 *       「22:00 到 23:00」这种半对的结果。
 * </ul>
 */
final class SlotGrid {

    private static final Logger log = LoggerFactory.getLogger(SlotGrid.class);

    /** 一格的长度：30 分钟（ADR-0049 §六）。 */
    static final int SLOT_MINUTES = 30;

    /** 一个时段的窗口。 */
    record Window(String startTime, String endTime) {
    }

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private SlotGrid() {
    }

    /** 某一星期几的时段网格；那天不营业（营业时段数组里没有它）时返回空。 */
    static List<Window> of(List<BusinessHour> hours, DayOfWeek day) {
        if (hours == null || hours.isEmpty()) {
            return List.of();
        }
        int iso = day.getValue();
        for (BusinessHour hour : hours) {
            if (hour != null && hour.dayOfWeek() != null && hour.dayOfWeek() == iso) {
                return of(hour);
            }
        }
        return List.of();
    }

    /** 一个营业时段的窗口列表；时间格式脏（解析不了）或跨天时返回空。 */
    static List<Window> of(BusinessHour hour) {
        String open = hour.openTime();
        String close = hour.closeTime();
        if (open == null || close == null || open.isBlank() || close.isBlank()) {
            return List.of();
        }
        LocalTime openTime;
        LocalTime closeTime;
        try {
            openTime = LocalTime.parse(open, HH_MM);
            closeTime = LocalTime.parse(close, HH_MM);
        } catch (DateTimeParseException e) {
            // 脏数据不该让整天的号源列表变成 50000：切不出来就是「那天没有可约时段」，
            // 但**必须留痕**——静默返回空会让门店以为平台丢了它的营业时间，而运维侧零信号。
            // 这条日志与 BusinessHours.decode（写入口那边的同一类脏数据）措辞对齐。
            log.warn("营业时间格式无法解析，按当天不可预约处理：dayOfWeek={} open={} close={}",
                    hour.dayOfWeek(), open, close);
            return List.of();
        }
        List<Window> windows = new ArrayList<>();
        if (!closeTime.isAfter(openTime)) {
            // 结束不晚于开始：要么跨天（22:00–02:00，本期不支持），要么填反了。
            // 两种都切不出「一天之内的时段」，显式返回空，别让循环在 LocalTime 上绕回原点。
            return List.of();
        }
        // **格数先算出来，不要拿 LocalTime 当循环变量**：`LocalTime` 没有日期，
        // `23:30.plusMinutes(30)` 会绕回 `00:00`——于是「还有没有下一格」这个判定永远成立，
        // 循环一路加下去直到 OOM（实测：下单与号源查询双双 50000，且把整个堆打爆，
        // 不是一次可恢复的报错）。触发条件不是脏数据，而是**正常的营业时间**：
        // 打烊时间落在 23:31–23:59（或正好 23:30）时最后一格必然跨越午夜。
        // Java 17 的 `LocalTime` 上这是个经典坑，`closeTime.isAfter(openTime)` 那条守卫拦不住它。
        int slots = (int) (java.time.Duration.between(openTime, closeTime).toMinutes() / SLOT_MINUTES);
        for (int i = 0; i < slots; i++) {
            LocalTime start = openTime.plusMinutes((long) i * SLOT_MINUTES);
            windows.add(new Window(start.format(HH_MM), start.plusMinutes(SLOT_MINUTES).format(HH_MM)));
        }
        return windows;
    }

    /** 网格里有没有这个开始时间（下单校验：不在营业时段内 → 40001）。 */
    static boolean containsStart(List<Window> windows, String startTime) {
        return windows.stream().anyMatch(window -> window.startTime().equals(startTime));
    }
}
