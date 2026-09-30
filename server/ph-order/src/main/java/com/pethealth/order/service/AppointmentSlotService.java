package com.pethealth.order.service;

import com.pethealth.api.order.AppointmentSlotView;
import com.pethealth.api.provider.BusinessHour;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.time.AppTime;
import com.pethealth.common.trace.TraceIds;
import com.pethealth.order.domain.AppointmentSlot;
import com.pethealth.order.mapper.AppointmentSlotMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 号源：查询时段网格、占用、释放（ADR-0038 第一节）。
 *
 * <p>三件事各有一个明确的责任人：
 *
 * <ul>
 *   <li><b>网格</b>（哪些时段可约）在 {@link SlotGrid}：纯计算，只依赖营业时间；
 *   <li><b>占用与释放</b>在 {@link AppointmentSlotMapper}：两条原子条件更新，
 *       并发抢同一时段只有一个能改到行；
 *   <li><b>本类</b>只做编排与边界判定：日期已过去 / 时段不在营业时间内 → 40001；
 *       行不存在就懒建一行；行存在但满了 → 40900「该时段已被预约」。
 * </ul>
 *
 * <p>隔离级别取 {@code READ_COMMITTED}（与 ph-privilege 的券额度同一口径）：条件更新本身
 * 是当前读、不受快照影响，但**失败后的回读**（判断是「行不存在」还是「已满」）必须看到最新
 * 已提交的状态，否则在默认的 REPEATABLE READ 下会命在本事务早先的快照上，
 * 把「刚被别人抢走」误报成别的分类（ADR-0044 记的就是这个坑）。
 */
@Service
public class AppointmentSlotService {

    private final AppointmentSlotMapper slotMapper;
    private final SlotRowWriter slotRows;
    private final ProviderFacts providerFacts;

    public AppointmentSlotService(AppointmentSlotMapper slotMapper, SlotRowWriter slotRows,
                                  ProviderFacts providerFacts) {
        this.slotMapper = slotMapper;
        this.slotRows = slotRows;
        this.providerFacts = providerFacts;
    }

    // ---------------------------------------------------------------- 查询

    /**
     * 某服务项在某天的可预约时段（契约 {@code GET /providers/{provider_id}/appointment-slots}）。
     *
     * <p>只返回营业时间内、属于该服务项、**还没过去的**时段；**约满的照样返回**
     * （`full=true`）——让它从列表里消失，用户会以为门店那天根本不营业（契约里点名的口径）。
     *
     * @throws BusinessException 40400 服务项不存在 / 不属于该门店 / 未上架；40001 日期已过去
     */
    public List<AppointmentSlotView> list(long providerId, long serviceId, LocalDate date) {
        // 服务项校验走接口（ADR-0006）：门店对不上、或没上架，一律按不存在处理
        providerFacts.requireListedService(providerId, serviceId);

        LocalDate today = AppTime.today();
        if (date.isBefore(today)) {
            throw BusinessException.paramInvalid("日期已过去，只能约今天及以后");
        }

        List<SlotGrid.Window> windows = windowsOf(providerId, date);
        if (windows.isEmpty()) {
            return List.of();
        }
        Map<String, AppointmentSlot> occupied = slotRows(providerId, serviceId, date);
        LocalDateTime now = AppTime.now();

        List<AppointmentSlotView> views = new ArrayList<>(windows.size());
        for (SlotGrid.Window window : windows) {
            if (!isFuture(date, window.startTime(), now)) {
                continue; // 已经过去的时段不返回（契约：「只返回……还没过去的时段」）
            }
            AppointmentSlot slot = occupied.get(window.startTime());
            int capacity = slot == null || slot.getCapacity() == null
                    ? AppointmentSlot.DEFAULT_CAPACITY : slot.getCapacity();
            int booked = slot == null || slot.getBookedCount() == null ? 0 : slot.getBookedCount();
            int available = Math.max(capacity - booked, 0);
            views.add(new AppointmentSlotView(date, window.startTime(), window.endTime(),
                    capacity, booked, available, available == 0));
        }
        return views;
    }

    // ---------------------------------------------------------------- 下单侧

    /**
     * 校验预约时段可约，并返回该时段的窗口（下单与号源查询共用同一份网格）。
     *
     * @throws BusinessException 40400 服务项不可选；40001 日期已过去 / 时段不在营业时间内 /
     *                           该时段已经开始
     */
    public SlotGrid.Window requireBookable(long providerId, long serviceId, LocalDate date, String startTime) {
        providerFacts.requireListedService(providerId, serviceId);

        LocalDateTime now = AppTime.now();
        if (date.isBefore(AppTime.today())) {
            throw BusinessException.paramInvalid("预约日期已过去");
        }
        List<SlotGrid.Window> windows = windowsOf(providerId, date);
        if (windows.isEmpty()) {
            throw BusinessException.paramInvalid("该门店当天不营业，无法预约");
        }
        for (SlotGrid.Window window : windows) {
            if (window.startTime().equals(startTime)) {
                if (!isFuture(date, startTime, now)) {
                    throw BusinessException.paramInvalid("该时段已经开始，请选择更晚的时段");
                }
                return window;
            }
        }
        throw BusinessException.paramInvalid("该时段不在门店营业时间内，可预约的时段见号源列表");
    }

    /**
     * 占用一个时段；**满了给 40900**（ADR-0038 第一节的并发口径）。
     *
     * <p>四步，每一步都在回答一个明确的问题（细节与踩过的两条坑见
     * {@link AppointmentSlotMapper} 的类注释）：
     *
     * <ol>
     *   <li>行已经在了吗？在了就直接争条件更新——**绝大多数请求走这一步**；
     *   <li>不在？那就懒建（**独立事务**，{@link SlotRowWriter}）——它的锁在这里已经释放，
     *       不会与后面那条 UPDATE 互相升级成死锁；
     *   <li>建完再争一次条件更新（并发下可能被别的请求抢先建出来，那正好一起争）；
     *   <li>还是没占到，就是「该时段已被预约」。
     * </ol>
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void occupy(long providerId, long serviceId, LocalDate date, SlotGrid.Window window) {
        long operatorId = TraceIds.currentOperatorId();
        String traceId = TraceIds.currentTraceId();
        LocalDateTime now = AppTime.now();

        if (slotMapper.tryOccupy(providerId, serviceId, date, window.startTime(),
                operatorId, traceId, now) > 0) {
            return;
        }
        if (slotMapper.countOf(providerId, serviceId, date, window.startTime()) == 0) {
            // 行还没建出来：先建（独立事务），再争一次
            slotRows.ensureRow(providerId, serviceId, date, window.startTime(), window.endTime());
            if (slotMapper.tryOccupy(providerId, serviceId, date, window.startTime(),
                    operatorId, traceId, now) > 0) {
                return;
            }
        }
        // 到这里只有一种可能：这一行已经在、而且已经满了（口径见 mapper 注释）
        throw BusinessException.conflict("该时段已被预约，请重新选择时段");
    }

    /** 释放一个时段（取消订单时）。幂等：只有真正推进了取消的那一次会调到这里。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void release(long providerId, long serviceId, LocalDate date, String startTime) {
        slotMapper.release(providerId, serviceId, date, startTime,
                TraceIds.currentOperatorId(), TraceIds.currentTraceId(), AppTime.now());
    }

    // ---------------------------------------------------------------- 内部

    /** 门店当天的营业网格。营业时间没配置时返回空（= 那天不可约）。 */
    private List<SlotGrid.Window> windowsOf(long providerId, LocalDate date) {
        List<BusinessHour> hours = providerFacts.businessHours(providerId);
        return SlotGrid.of(hours, date.getDayOfWeek());
    }

    /** 该 (门店, 服务项, 日期) 下已落库的时段行，键是开始时间。 */
    private Map<String, AppointmentSlot> slotRows(long providerId, long serviceId, LocalDate date) {
        List<AppointmentSlot> rows = slotMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AppointmentSlot>()
                        .eq(AppointmentSlot::getProviderId, providerId)
                        .eq(AppointmentSlot::getServiceId, serviceId)
                        .eq(AppointmentSlot::getSlotDate, date));
        Map<String, AppointmentSlot> byStart = new HashMap<>();
        for (AppointmentSlot row : rows) {
            byStart.put(row.getStartTime(), row);
        }
        return byStart;
    }

    /** 时段是否还没开始（下单与查询共用：已经开始或过去的时段都不能再约）。 */
    private static boolean isFuture(LocalDate date, String startTime, LocalDateTime now) {
        LocalDateTime start = LocalDateTime.of(date, java.time.LocalTime.parse(startTime));
        return start.isAfter(now);
    }
}
