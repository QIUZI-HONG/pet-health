package com.pethealth.record.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 档案模块为**提醒生成**提供的只读查询（切片 #99）。
 *
 * <p>存在的理由与 {@link PetQueryApi} 一样：提醒模块需要宠物的年龄/慢病、防疫到期日与体重趋势，
 * 但不能直连档案模块的表（ADR-0006）。这里是那三件事的唯一出口。
 *
 * <p>**只读、只给提醒用**；不要把它扩成通用查询口子——需要别的东西时，先在档案模块里想清楚归属。
 */
public interface ReminderSourceApi {

    /** 一只宠物的轻量信息（提醒规则只用到这两三个字段）。 */
    record PetBrief(long petId, long userId, String name, LocalDate birthday, boolean chronic) {
    }

    /** 一条「快到期」的防疫记录。{@code kind} 1=疫苗 2=驱虫。 */
    record DueItem(long recordId, int kind, String name, LocalDate dueOn) {
    }

    /** 体重区间（用来算变化幅度）；没有记录时返回 null。 */
    record WeightRange(BigDecimal min, BigDecimal max) {
    }

    List<PetBrief> listPetsOf(long userId);

    /** 所有有宠物的用户 id——每日批算要遍历它们（按用户量分批的事属将来，见 ADR-0019）。 */
    List<Long> listActiveUserIds();

    PetBrief findPet(long petId);

    /** [from, to] 内到期的防疫记录（due_on 非空且落在窗口内）。 */
    List<DueItem> dueItems(long petId, LocalDate from, LocalDate to);

    /** [from, to] 内的体重区间。 */
    WeightRange weightRange(long petId, LocalDate from, LocalDate to);

    /** 某天是否已有记录——「日常（今天还没记录）」提醒的依据。 */
    boolean hasRecordOn(long petId, LocalDate date);
}
