package com.pethealth.order.service;

import com.pethealth.common.trace.TraceIds;
import com.pethealth.order.domain.AppointmentSlot;
import com.pethealth.order.mapper.AppointmentSlotMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 号源行的**懒建**：把「这个时段第一次被预约」这件事放到一个**独立事务**里做。
 *
 * <p>为什么要独立事务（{@code REQUIRES_NEW}）而不是直接在下单事务里插一行：
 *
 * <ul>
 *   <li><b>避开 MySQL 的经典死锁</b>：在同一个事务里「INSERT 失败（唯一键）→ 再 UPDATE 同一行」
 *       会留下共享锁并试图升级成排他锁，多个并发请求互相等对方的锁。实测 4 个并发抢同一时段，
 *       有一个拿到 50000。把建行挪进独立事务后，它的锁在条件更新之前就已释放，
 *       剩下的只有「多条 UPDATE 排同一条队列」——那是排队，不是死锁；
 *   <li><b>失败的代价可控</b>：这一行建出来时 {@code booked_count = 0}（**不占用**），
 *       所以即使外层下单事务随后回滚，留下的也只是「一个还没被占的时段」，
 *       不会变成幽灵占用（占用的 {@code +1} 在条件更新里，随外层一起回滚）。
 * </ul>
 *
 * <p>为什么单独一个类：{@code @Transactional} 是 Spring 代理生效的，同一个类里自调用不会开新事务——
 * 这是本项目里唯一需要新事务的地方，值得单独放一个类，而不是在 service 里塞一个看不出所以然的注解。
 */
@Component
public class SlotRowWriter {

    private final AppointmentSlotMapper slotMapper;

    public SlotRowWriter(AppointmentSlotMapper slotMapper) {
        this.slotMapper = slotMapper;
    }

    /**
     * 确保这一行存在（存在则什么都不做）。
     *
     * @param date    时段网格已经算好，所以 {@code endTime} 由调用方给定（不是猜出来的）
     * @return true 表示本次真的建出来了（调用方不需要这个结论，留着给日志与测试看）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public boolean ensureRow(long providerId, long serviceId, LocalDate date,
                             String startTime, String endTime) {
        return slotMapper.insertIfAbsent(providerId, serviceId, date, startTime, endTime,
                AppointmentSlot.DEFAULT_CAPACITY, TraceIds.currentOperatorId(),
                TraceIds.currentTraceId()) > 0;
    }
}
