package com.pethealth.record.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pethealth.record.api.ReminderSourceApi;
import com.pethealth.record.domain.ArchiveRecord;
import com.pethealth.record.domain.Pet;
import com.pethealth.record.mapper.ArchiveRecordMapper;
import com.pethealth.common.util.JsonFields;
import com.pethealth.record.mapper.PetMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link ReminderSourceApi} 的实现：把提醒生成所需的三类查询收在这里。
 *
 * <p>查询都用 MyBatis-Plus 的 Wrapper 完成，逻辑删除自动过滤。体重区间目前是**把窗口内的
 * 体重记录取回来再算 min/max**（窗口只有 7 天，一条宠物最多 7 行）——量级对得起这样写；
 * 换成整表统计时要改成 SQL 聚合。
 */
@Service
public class ReminderSourceService implements ReminderSourceApi {

    private final PetMapper petMapper;
    private final ArchiveRecordMapper recordMapper;

    public ReminderSourceService(PetMapper petMapper, ArchiveRecordMapper recordMapper) {
        this.petMapper = petMapper;
        this.recordMapper = recordMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PetBrief> listPetsOf(long userId) {
        List<PetBrief> briefs = new ArrayList<>();
        for (Pet pet : petMapper.selectList(Wrappers.<Pet>lambdaQuery().eq(Pet::getUserId, userId))) {
            briefs.add(toBrief(pet));
        }
        return briefs;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> listActiveUserIds() {
        // 只取「有宠物」的用户：没有宠物的用户没有任何提醒规则可跑（提醒都挂在宠物上）。
        // 去重与排序都在 SQL 里做（见 PetMapper.selectActiveUserIds）——这个方法是每日批算的入口，
        // 而「把整张 pet 表捞回内存再 distinct」的写法会随宠物总数线性吃堆
        return petMapper.selectActiveUserIds();
    }

    @Override
    @Transactional(readOnly = true)
    public PetBrief findPet(long petId) {
        Pet pet = petMapper.selectById(petId);
        return pet == null ? null : toBrief(pet);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DueItem> dueItems(long petId, LocalDate from, LocalDate to) {
        List<ArchiveRecord> records = recordMapper.selectList(Wrappers.<ArchiveRecord>lambdaQuery()
                .eq(ArchiveRecord::getPetId, petId)
                .eq(ArchiveRecord::getCategory, ArchiveRecord.CATEGORY_EPIDEMIC)
                .isNotNull(ArchiveRecord::getDueOn)
                .between(ArchiveRecord::getDueOn, from, to)
                .orderByAsc(ArchiveRecord::getDueOn));
        List<DueItem> items = new ArrayList<>();
        for (ArchiveRecord record : records) {
            items.add(new DueItem(record.getId(), kindOf(record), nameOf(record), record.getDueOn()));
        }
        return items;
    }

    @Override
    @Transactional(readOnly = true)
    public WeightRange weightRange(long petId, LocalDate from, LocalDate to) {
        List<ArchiveRecord> weights = recordMapper.selectList(Wrappers.<ArchiveRecord>lambdaQuery()
                .eq(ArchiveRecord::getPetId, petId)
                .eq(ArchiveRecord::getCategory, ArchiveRecord.CATEGORY_WEIGHT)
                .isNotNull(ArchiveRecord::getNumericValue)
                .between(ArchiveRecord::getRecordDate, from, to));
        if (weights.isEmpty()) {
            return null;
        }
        BigDecimal min = weights.get(0).getNumericValue();
        BigDecimal max = min;
        for (ArchiveRecord record : weights) {
            BigDecimal value = record.getNumericValue();
            if (value.compareTo(min) < 0) {
                min = value;
            }
            if (value.compareTo(max) > 0) {
                max = value;
            }
        }
        return new WeightRange(min, max);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasRecordOn(long petId, LocalDate date) {
        return recordMapper.selectCount(Wrappers.<ArchiveRecord>lambdaQuery()
                .eq(ArchiveRecord::getPetId, petId)
                .eq(ArchiveRecord::getRecordDate, date)) > 0;
    }

    private PetBrief toBrief(Pet pet) {
        return new PetBrief(pet.getId(), pet.getUserId(), pet.getName(), pet.getBirthday(),
                pet.getIsChronic() != null && pet.getIsChronic() == 1);
    }

    /** 防疫记录的类型与名称都存在 content 里（{@code {"kind":"vaccine","name":"狂犬疫苗"}}）。 */
    private int kindOf(ArchiveRecord record) {
        return ArchiveRecord.EPIDEMIC_DEWORM.equals(epidemicKind(record)) ? 2 : 1;
    }

    private String nameOf(ArchiveRecord record) {
        String name = JsonFields.read(record.getContent(), "name");
        return name == null || name.isBlank() ? "防疫记录" : name;
    }

    private String epidemicKind(ArchiveRecord record) {
        String kind = JsonFields.read(record.getContent(), "kind");
        return kind == null ? ArchiveRecord.EPIDEMIC_VACCINE : kind;
    }

}
