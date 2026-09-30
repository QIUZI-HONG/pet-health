package com.pethealth.record.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pethealth.api.app.EpidemicRecordRequest;
import com.pethealth.api.app.EpidemicRecordView;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.time.AppTime;
import com.pethealth.common.util.JsonFields;
import com.pethealth.record.domain.ArchiveRecord;
import com.pethealth.record.mapper.ArchiveRecordMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 防疫记录：疫苗 / 驱虫日期（切片 #99 顺带做的最小录入）。
 *
 * <p>为什么现在做：疫苗到期提醒是提醒体系里用户最认的一类（ADR-0019），而它只需要两个日期；
 * 顺带把健康评分的「防疫」维度从「待录入」变成可计分（切片 #97 留下的口子）。
 *
 * <p>{@code nextDueOn} **不填就没有提醒**——不同疫苗周期不同，替用户猜比不提醒更糟。
 */
@Service
public class EpidemicRecordService {

    private final ArchiveRecordMapper recordMapper;
    private final PetService petService;
    private final HealthScoreService healthScoreService;

    public EpidemicRecordService(ArchiveRecordMapper recordMapper,
                                PetService petService,
                                HealthScoreService healthScoreService) {
        this.recordMapper = recordMapper;
        this.petService = petService;
        this.healthScoreService = healthScoreService;
    }

    @Transactional(readOnly = true)
    public List<EpidemicRecordView> list(long userId, long petId) {
        petService.requireOwned(userId, petId);
        List<ArchiveRecord> records = recordMapper.selectList(Wrappers.<ArchiveRecord>lambdaQuery()
                .eq(ArchiveRecord::getPetId, petId)
                .eq(ArchiveRecord::getCategory, ArchiveRecord.CATEGORY_EPIDEMIC)
                .orderByDesc(ArchiveRecord::getRecordDate));
        List<EpidemicRecordView> views = new ArrayList<>();
        for (ArchiveRecord record : records) {
            views.add(toView(record));
        }
        return views;
    }

    @Transactional
    public EpidemicRecordView create(long userId, long petId, EpidemicRecordRequest input) {
        petService.requireOwned(userId, petId);
        // 日期一律走 AppTime.parseDate：格式对但日子不存在（2026-02-31）要给 40001 而不是 50000
        LocalDate givenOn = AppTime.parseDate(input.givenOn());
        if (givenOn.isAfter(AppTime.today())) {
            throw BusinessException.paramInvalid("接种日期不能晚于今天");
        }
        LocalDate dueOn = input.nextDueOn() == null || input.nextDueOn().isBlank()
                ? null
                : AppTime.parseDate(input.nextDueOn());
        if (dueOn != null && dueOn.isBefore(givenOn)) {
            throw BusinessException.paramInvalid("下次应接种日期不能早于本次日期");
        }

        ArchiveRecord record = new ArchiveRecord();
        record.setPetId(petId);
        record.setUserId(userId);
        record.setRecordDate(givenOn);
        record.setCategory(ArchiveRecord.CATEGORY_EPIDEMIC);
        record.setContent(buildContent(input.kind(), input.name()));
        record.setDueOn(dueOn);
        record.setSource(ArchiveRecord.SOURCE_USER);
        recordMapper.insert(record);

        // 防疫记录进评分的数据源，所以写完立刻重算（与打卡同一条路径）
        healthScoreService.recalculate(petId, AppTime.today());
        return toView(record);
    }

    @Transactional
    public void delete(long userId, long petId, long recordId) {
        petService.requireOwned(userId, petId);
        // 幂等：不存在也返回成功；软删除保留历史（ADR-0011）
        recordMapper.delete(Wrappers.<ArchiveRecord>lambdaQuery()
                .eq(ArchiveRecord::getId, recordId)
                .eq(ArchiveRecord::getPetId, petId)
                .eq(ArchiveRecord::getCategory, ArchiveRecord.CATEGORY_EPIDEMIC));
        healthScoreService.recalculate(petId, AppTime.today());
    }

    private EpidemicRecordView toView(ArchiveRecord record) {
        Integer kind = ArchiveRecord.EPIDEMIC_DEWORM.equals(kindOf(record)) ? 2 : 1;
        String name = JsonFields.read(record.getContent(), "name");
        Integer daysUntilDue = record.getDueOn() == null
                ? null
                : (int) ChronoUnit.DAYS.between(AppTime.today(), record.getDueOn());
        return new EpidemicRecordView(record.getId(), kind, name, record.getRecordDate(),
                record.getDueOn(), daysUntilDue);
    }

    private String buildContent(int kind, String name) {
        String type = kind == 2 ? ArchiveRecord.EPIDEMIC_DEWORM : ArchiveRecord.EPIDEMIC_VACCINE;
        return JsonFields.write(Map.of("kind", type, "name", name));
    }

    private String kindOf(ArchiveRecord record) {
        return JsonFields.read(record.getContent(), "kind");
    }
}
