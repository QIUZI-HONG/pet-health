package com.pethealth.record.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pethealth.api.app.CheckInDay;
import com.pethealth.api.app.CheckInItem;
import com.pethealth.api.app.CheckInItemInput;
import com.pethealth.api.app.CheckInStreak;
import com.pethealth.api.app.CheckInSubmitRequest;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.time.AppTime;
import com.pethealth.common.trace.TraceIds;
import com.pethealth.common.util.JsonFields;
import com.pethealth.record.domain.ArchiveRecord;
import com.pethealth.record.domain.Pet;
import com.pethealth.record.domain.Weight;
import com.pethealth.record.event.CheckInRecordedEvent;
import com.pethealth.record.mapper.ArchiveRecordMapper;
import com.pethealth.record.mapper.PetMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.pethealth.record.domain.ArchiveRecord.CATEGORY_BEHAVIOR;
import static com.pethealth.record.domain.ArchiveRecord.CATEGORY_DIET;
import static com.pethealth.record.domain.ArchiveRecord.CATEGORY_EXCRETION;
import static com.pethealth.record.domain.ArchiveRecord.CATEGORY_HYGIENE;
import static com.pethealth.record.domain.ArchiveRecord.CATEGORY_MOOD;
import static com.pethealth.record.domain.ArchiveRecord.CATEGORY_WEIGHT;

/**
 * 打卡：六项健康任务的提交、查询、撤销与连续天数（切片 #97，规则见 ADR-0018）。
 *
 * <p>三条规则是这个类的骨架，改动前先读 ADR：
 *
 * <ol>
 *   <li><b>任一项即算当日已打卡</b>——不要求凑齐六项。桌面 Web 上强制六项的结果是没人打卡。
 *   <li><b>幂等</b>：同「宠物 + 业务日期 + 分项」是更新而不是新增。用户改主意（饮食从「正常」
 *       改成「食欲差」）就是改这一条，而不是留两条互相矛盾的记录。
 *   <li><b>补录窗口 7 天</b>：业务日期只能是今天或过去 7 天。窗口外拒绝（40001），
 *       否则历史评分会被随意改写。
 * </ol>
 */
@Service
public class CheckInService {

    /** 可补录的天数（含今天共 8 天可选）。改这个值要同步 ADR-0018 与契约描述。 */
    public static final int BACKFILL_WINDOW_DAYS = 7;

    /** 打卡的六项及其展示名，顺序就是前端列表顺序。 */
    private static final Map<Integer, String> CHECK_IN_CATEGORIES = new LinkedHashMap<>();

    static {
        CHECK_IN_CATEGORIES.put(CATEGORY_WEIGHT, "体重");
        CHECK_IN_CATEGORIES.put(CATEGORY_DIET, "饮食");
        CHECK_IN_CATEGORIES.put(CATEGORY_EXCRETION, "排泄");
        CHECK_IN_CATEGORIES.put(CATEGORY_BEHAVIOR, "行为");
        CHECK_IN_CATEGORIES.put(CATEGORY_MOOD, "情绪");
        CHECK_IN_CATEGORIES.put(CATEGORY_HYGIENE, "卫生");
    }

    private final ArchiveRecordMapper recordMapper;
    private final PetMapper petMapper;
    private final HealthScoreService healthScoreService;
    private final ApplicationEventPublisher eventPublisher;

    public CheckInService(ArchiveRecordMapper recordMapper,
                          PetMapper petMapper,
                          HealthScoreService healthScoreService,
                          ApplicationEventPublisher eventPublisher) {
        this.recordMapper = recordMapper;
        this.petMapper = petMapper;
        this.healthScoreService = healthScoreService;
        this.eventPublisher = eventPublisher;
    }

    /** 查某一天的打卡状态。不传日期则按服务器当天（Asia/Shanghai）。 */
    @Transactional(readOnly = true)
    public CheckInDay day(long userId, long petId, LocalDate date) {
        requireOwnedPet(userId, petId);
        LocalDate target = date == null ? AppTime.today() : date;
        validateWindow(target);

        Map<Integer, ArchiveRecord> byCategory = recordsOfDay(petId, target);
        List<CheckInItem> items = new ArrayList<>();
        int completed = 0;
        for (Map.Entry<Integer, String> entry : CHECK_IN_CATEGORIES.entrySet()) {
            ArchiveRecord record = byCategory.get(entry.getKey());
            if (record != null) {
                completed++;
            }
            items.add(new CheckInItem(
                    entry.getKey(),
                    entry.getValue(),
                    record != null,
                    record != null && record.isAbnormalFlag(),
                    record == null ? null : valueOf(record),
                    record == null ? null : noteOf(record),
                    record != null && record.isBackfilledFlag()));
        }
        return new CheckInDay(target.toString(), items, completed, CHECK_IN_CATEGORIES.size(),
                completed > 0, target.isBefore(AppTime.today()));
    }

    /**
     * 提交打卡（幂等）。提交后立刻重算当日评分——打卡要有即时反馈（F005）。
     */
    @Transactional
    public CheckInDay submit(long userId, long petId, CheckInSubmitRequest request) {
        Pet pet = requireOwnedPet(userId, petId);
        LocalDate date = AppTime.parseDate(request.date());
        validateWindow(date);
        boolean backfilled = date.isBefore(AppTime.today());

        List<Integer> abnormalCategories = new ArrayList<>();
        for (CheckInItemInput item : request.items()) {
            upsert(userId, petId, date, item, backfilled);
            if (Boolean.TRUE.equals(item.abnormal())) {
                abnormalCategories.add(item.category());
            }
        }
        recalculateScores(petId, date);
        if (!abnormalCategories.isEmpty()) {
            // 发事件而不是直接调提醒模块：档案模块不该知道提醒存在（ADR-0006）
            eventPublisher.publishEvent(new CheckInRecordedEvent(
                    userId, petId, pet.getName(), date, abnormalCategories));
        }
        return day(userId, petId, date);
    }

    /** 撤销某一项（填错了）。幂等：本来就没有也返回成功。 */
    @Transactional
    public CheckInDay undo(long userId, long petId, LocalDate date, int category) {
        requireOwnedPet(userId, petId);
        LocalDate target = date == null ? AppTime.today() : date;
        validateWindow(target);
        if (!CHECK_IN_CATEGORIES.containsKey(category)) {
            throw BusinessException.paramInvalid("category 只能是 1–6（打卡的六项）");
        }
        // 逻辑删除：历史留痕不丢（ADR-0011「物理删除仅限账号注销」）
        recordMapper.delete(Wrappers.<ArchiveRecord>lambdaQuery()
                .eq(ArchiveRecord::getPetId, petId)
                .eq(ArchiveRecord::getRecordDate, target)
                .eq(ArchiveRecord::getCategory, category));
        healthScoreService.recalculate(petId, AppTime.today());
        return day(userId, petId, target);
    }

    /**
     * 连续打卡天数。
     *
     * <p>今天没打卡不算断签：昨天有记录就仍算连续（用户还没到打卡的时候）。断签只在
     * 「昨天与今天都没有记录」时发生。
     */
    @Transactional(readOnly = true)
    public CheckInStreak streak(long userId, long petId) {
        requireOwnedPet(userId, petId);
        LocalDate today = AppTime.today();
        List<LocalDate> dates = recordMapper.selectRecordDatesDesc(petId, today);
        boolean checkedToday = !dates.isEmpty() && dates.get(0).isEqual(today);

        // 从今天（或昨天）往前数连续的自然日
        LocalDate cursor = checkedToday ? today : today.minusDays(1);
        int streak = 0;
        for (LocalDate date : dates) {
            if (date.isEqual(cursor)) {
                streak++;
                cursor = cursor.minusDays(1);
            } else if (date.isBefore(cursor)) {
                break;
            }
        }
        return new CheckInStreak(streak, checkedToday, longestStreak(dates));
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 重算评分：**既算记录那天的，也算今天的**。
     *
     * <p>补录时如果只算今天，历史那天就没有分数行，趋势图上会缺一格（而趋势是按天存的）。
     * 两行都很便宜（各一次聚合查询），所以不用纠结。
     */
    private void recalculateScores(long petId, LocalDate recordDate) {
        LocalDate today = AppTime.today();
        healthScoreService.recalculate(petId, recordDate);
        if (!recordDate.isEqual(today)) {
            healthScoreService.recalculate(petId, today);
        }
    }

    /**
     * 写一个分项。**一条原子 SQL 搞定三种情况**（同槽更新 / 撤销后复活 / 新增）：
     *
     * <ul>
     *   <li>「先查再写」在并发下会撞 {@code uk_checkin_slot}，而撞唯一键会把整个事务标记成
     *       rollback-only——接住异常也救不回来，提交时照样 500（已复现）；</li>
     *   <li>撤销是逻辑删除，但唯一键占着那个槽，所以复活必须由「冲突时 UPDATE」来完成；</li>
     *   <li>体重的值先过 {@link Weight}：非法值在这里就被挡下（40001），
     *       不再静默存成 null 让趋势算出荒谬数字（测试报告 D1）。</li>
     * </ul>
     */
    private void upsert(long userId, long petId, LocalDate date, CheckInItemInput item, boolean backfilled) {
        boolean abnormal = Boolean.TRUE.equals(item.abnormal());
        BigDecimal weight = item.category() == CATEGORY_WEIGHT ? Weight.parse(item.value()) : null;

        recordMapper.upsertCheckIn(petId, userId, date, item.category(), buildContent(item),
                abnormal ? 1 : 0, backfilled ? 1 : 0, weight,
                AppTime.now(), TraceIds.currentOperatorId(), TraceIds.currentTraceId());
    }

    /**
     * 取值与备注存成 JSON。用 JSON 而不是固定列，是因为六项的形状不同（体重是数字、
     * 其余是选项），而交付文档的 DDL 本来就是 {@code content TEXT}。
     */
    private String buildContent(CheckInItemInput item) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("status", Boolean.TRUE.equals(item.abnormal()) ? "abnormal" : "normal");
        if (item.value() != null && !item.value().isBlank()) {
            fields.put("value", item.value());
        }
        if (item.note() != null && !item.note().isBlank()) {
            fields.put("note", item.note());
        }
        return JsonFields.write(fields);
    }

    private String valueOf(ArchiveRecord record) {
        return JsonFields.read(record.getContent(), "value");
    }

    private String noteOf(ArchiveRecord record) {
        return JsonFields.read(record.getContent(), "note");
    }

    private Map<Integer, ArchiveRecord> recordsOfDay(long petId, LocalDate date) {
        List<ArchiveRecord> records = recordMapper.selectList(Wrappers.<ArchiveRecord>lambdaQuery()
                .eq(ArchiveRecord::getPetId, petId)
                .eq(ArchiveRecord::getRecordDate, date));
        Map<Integer, ArchiveRecord> byCategory = new LinkedHashMap<>();
        for (ArchiveRecord record : records) {
            byCategory.put(record.getCategory(), record);
        }
        return byCategory;
    }

    /** 业务日期只能落在 [今天-7, 今天]：窗口外拒绝，免得历史评分被随意改写。 */
    private void validateWindow(LocalDate date) {
        LocalDate today = AppTime.today();
        if (date.isAfter(today)) {
            throw BusinessException.paramInvalid("不能给将来的日期打卡");
        }
        if (date.isBefore(today.minusDays(BACKFILL_WINDOW_DAYS))) {
            throw BusinessException.paramInvalid(
                    "只能补录最近 " + BACKFILL_WINDOW_DAYS + " 天的记录");
        }
    }

    /**
     * 归属校验：越权与不存在都按 404 处理，不泄露 id 是否存在（契约里写明了）。
     * 返回宠物实体是因为打卡相关的判断（老年专项等）需要它的字段。
     */
    private Pet requireOwnedPet(long userId, long petId) {
        Pet pet = petMapper.selectOne(Wrappers.<Pet>lambdaQuery()
                .eq(Pet::getId, petId)
                .eq(Pet::getUserId, userId));
        if (pet == null) {
            throw BusinessException.notFound();
        }
        return pet;
    }

    /** 历史最长连续天数：给「别断了」一点参照。 */
    private int longestStreak(List<LocalDate> datesDesc) {
        int longest = 0;
        int current = 0;
        LocalDate previous = null;
        for (LocalDate date : datesDesc) {
            if (previous != null && date.plusDays(1).isEqual(previous)) {
                current++;
            } else {
                current = 1;
            }
            longest = Math.max(longest, current);
            previous = date;
        }
        return longest;
    }
}
