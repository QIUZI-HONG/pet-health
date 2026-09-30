package com.pethealth.order.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

import java.time.LocalDate;

/**
 * 号源：服务项在某天某时段上的**容量单位**（表 {@code appointment_slot}，迁移 V28；
 * 语义见 CONTEXT.md 的「号源」条目与 ADR-0038 第一节）。
 *
 * <p>三条要记住的：
 *
 * <ul>
 *   <li><b>占用与释放都是原子条件更新</b>：{@code booked_count < capacity} 才加，
 *       {@code booked_count > 0} 才减。这是「并发抢同一时段只有一个成功」的全部实现——
 *       没有锁、没有版本号，靠的是数据库行锁 + 条件；
 *   <li><b>行是懒建的</b>：时段网格由营业时间算出来（{@code AppointmentSlotService}），
 *       第一次被预约时才落行，所以「30 天后的空时段」不会提前占满整张表；
 *   <li><b>{@code bookedCount} 含履约中与已完成的订单</b>：服务做完了也不释放号源
 *       （那段营业时间被用掉了），只有取消才 {@code -1}。
 * </ul>
 *
 * <p>{@code capacity} 默认 1 是**实现侧定的最小形态**（ADR-0038 只写了「按时段容量占用」，
 * 没写一个时段能约几个、由谁给；契约里也标着待澄清），见 ADR-0048 的「待澄清」。
 */
@TableName("appointment_slot")
public class AppointmentSlot extends BaseEntity {

    /** 一个时段的默认容量。改它要连迁移 V28 的列默认值一起改（存量行按行上的值走）。 */
    public static final int DEFAULT_CAPACITY = 1;

    private Long providerId;
    private Long serviceId;
    private LocalDate slotDate;
    private String startTime;
    private String endTime;
    private Integer capacity;
    private Integer bookedCount;

    /** 还能约几个 = 容量 − 已占用（契约里的 {@code available_count}）。 */
    public int availableCount() {
        int capacity = this.capacity == null ? DEFAULT_CAPACITY : this.capacity;
        int booked = this.bookedCount == null ? 0 : this.bookedCount;
        return Math.max(capacity - booked, 0);
    }

    /** 是否已约满（契约里的 {@code full}：**约满的时段照样返回**，前端要显示「已满」）。 */
    public boolean isFull() {
        return availableCount() == 0;
    }

    public Long getProviderId() {
        return providerId;
    }

    public void setProviderId(Long providerId) {
        this.providerId = providerId;
    }

    public Long getServiceId() {
        return serviceId;
    }

    public void setServiceId(Long serviceId) {
        this.serviceId = serviceId;
    }

    public LocalDate getSlotDate() {
        return slotDate;
    }

    public void setSlotDate(LocalDate slotDate) {
        this.slotDate = slotDate;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }

    public Integer getCapacity() {
        return capacity;
    }

    public void setCapacity(Integer capacity) {
        this.capacity = capacity;
    }

    public Integer getBookedCount() {
        return bookedCount;
    }

    public void setBookedCount(Integer bookedCount) {
        this.bookedCount = bookedCount;
    }
}
