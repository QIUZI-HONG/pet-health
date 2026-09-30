package com.pethealth.record.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pethealth.api.ai.AiConsultStatsApi;
import com.pethealth.api.app.HealthReportPayload;
import com.pethealth.api.app.HealthReportStats;
import com.pethealth.api.app.HealthReportView;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.time.AppTime;
import com.pethealth.common.trace.TraceIds;
import com.pethealth.record.domain.ArchiveRecord;
import com.pethealth.record.domain.CareMode;
import com.pethealth.record.domain.HealthReport;
import com.pethealth.record.domain.HealthScore;
import com.pethealth.record.domain.Pet;
import com.pethealth.record.mapper.ArchiveRecordMapper;
import com.pethealth.record.mapper.HealthReportMapper;
import com.pethealth.record.mapper.HealthScoreMapper;
import com.pethealth.record.mapper.PetMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 健康报告：周报与月报（切片 #115，决策见 ADR-0031）。
 *
 * <pre>
 * 周报 = 上一个完整自然周（周一至周日）      月报 = 上一个完整自然月
 * </pre>
 *
 * <p>四条纪律，改动前先读 ADR：
 *
 * <ol>
 *   <li><b>只做两种报告</b>，且周期取**上一个完整周期**（拍板口径是「每周一生成上周」）：
 *       还在走的周期里，「完成度」「异常次数」每天都变，用户没法拿它跟上一期比；
 *   <li><b>规则模板拼装、不调模型</b>：报告里的每个数字都来自数据，模型在这里只能产生幻觉、
 *       不能产生信息（措辞也在代码里，入库可改属 #118）；
 *   <li><b>幂等且不回改历史</b>：唯一键 + {@code ON DUPLICATE KEY UPDATE id = id}，
 *       同一周期已经生成过就原样保留——报告记的是**当时**的口径；
 *   <li><b>权益只标注不拦截</b>：完整版的段落照常返回，前端标解锁（ADR-0024 第一条的同一判断）。
 * </ol>
 */
@Service
public class HealthReportService {

    private static final Logger log = LoggerFactory.getLogger(HealthReportService.class);

    /** 保留最近 N 期（每种报告各 12 期，够回看一年周报 / 一年月报）。 */
    public static final int KEEP_PERIODS = 12;

    /** 完整版对应的权益码（与 #80 的权益码表对齐）。 */
    public static final String PRIVILEGE_REPORT_FULL = "report.full";

    private static final String TIER_NOTE = "完整版内容（异常与亮点、建议清单）将在权益上线后按邀请解锁，现在可以先看。";

    private static final String NOTICE = "报告来自你的记录与平台评分，用于观察趋势，不能替代兽医诊断；"
            + "建议清单是行动项，不是诊断或用药建议。";

    /** 提醒阈值：疫苗/驱虫到期前多少天开始提示（与提醒体系的提前量无关，报告只做「临近」提示）。 */
    private static final int DUE_SOON_DAYS = 30;

    private final HealthReportMapper reportMapper;
    private final ArchiveRecordMapper recordMapper;
    private final HealthScoreMapper scoreMapper;
    private final PetMapper petMapper;
    private final PetService petService;
    private final CareModeService careModeService;
    private final AiConsultStatsApi aiStats;

    public HealthReportService(HealthReportMapper reportMapper, ArchiveRecordMapper recordMapper,
                               HealthScoreMapper scoreMapper, PetMapper petMapper,
                               PetService petService, CareModeService careModeService,
                               AiConsultStatsApi aiStats) {
        this.reportMapper = reportMapper;
        this.recordMapper = recordMapper;
        this.scoreMapper = scoreMapper;
        this.petMapper = petMapper;
        this.petService = petService;
        this.careModeService = careModeService;
        this.aiStats = aiStats;
    }

    /**
     * 报告列表（倒序分页）。
     *
     * <p>读的时候**惰性补齐**最近一个完整周期（与提醒的惰性补算是同一模式）：定时任务没跑到
     * （停机、迁移、部署窗口）就会永久缺一期，而历史报告不像提醒可以下次补上——它对应的是一个
     * 已经结束的周期。补齐与定时任务调同一个方法、共用同一个幂等键，所以不会重复。
     *
     * @param type 报告类型；null 表示两类都补齐并返回全部
     */
    @Transactional
    public PageResult<HealthReportView> list(long userId, long petId, Integer type,
                                             long page, long pageSize) {
        Pet pet = petService.requireOwned(userId, petId);
        if (type != null && type != HealthReport.TYPE_WEEKLY && type != HealthReport.TYPE_MONTHLY) {
            throw BusinessException.paramInvalid("报告类型只能是 1（周报）或 2（月报）");
        }
        LocalDate today = AppTime.today();
        if (type == null || type == HealthReport.TYPE_WEEKLY) {
            generate(pet, HealthReport.TYPE_WEEKLY, today);
        }
        if (type == null || type == HealthReport.TYPE_MONTHLY) {
            generate(pet, HealthReport.TYPE_MONTHLY, today);
        }

        var query = Wrappers.<HealthReport>lambdaQuery().eq(HealthReport::getPetId, petId);
        if (type != null) {
            query.eq(HealthReport::getType, type);
        }
        query.orderByDesc(HealthReport::getPeriodStart);
        IPage<HealthReport> result = reportMapper.selectPage(new Page<>(page, pageSize), query);
        return PageResult.from(result, HealthReportService::toView);
    }

    /**
     * 定时任务用：为所有宠物的报告做一次「上一个完整周期」的生成（幂等，重复跑不会重复出报告）。
     *
     * <p>分批扫宠物（每批 200）而不是一次查全表：宠物表会随用户增长，而生成过程对每只宠物
     * 要读它这一期的记录与评分——一次性把全部宠物读进内存没有意义。
     */
    @Transactional
    public int generateForAllPets() {
        LocalDate today = AppTime.today();
        int created = 0;
        long current = 1;
        while (true) {
            Page<Pet> batch = petMapper.selectPage(new Page<>(current, 200),
                    Wrappers.<Pet>lambdaQuery().orderByAsc(Pet::getId));
            if (batch.getRecords().isEmpty()) {
                break;
            }
            for (Pet pet : batch.getRecords()) {
                created += generate(pet, HealthReport.TYPE_WEEKLY, today);
                created += generate(pet, HealthReport.TYPE_MONTHLY, today);
            }
            if (batch.getRecords().size() < 200) {
                break;
            }
            current++;
        }
        log.info("健康报告批算完成：新增 {} 期", created);
        return created;
    }

    // ---------------------------------------------------------------- 生成

    /**
     * 生成某个类型「上一个完整周期」的报告（幂等）。
     *
     * @return 1 表示这次写入了新的一期，0 表示该周期已有报告（原样保留）
     */
    @Transactional
    public int generate(Pet pet, int type, LocalDate today) {
        Period period = periodOf(type, today);
        Window current = windowOf(pet.getId(), period.start(), period.end());
        Window previous = windowOf(pet.getId(),
                period.start().minusDays(period.days()), period.start().minusDays(1));

        HealthReportPayload payload = buildPayload(pet, period, current, previous, today);
        int written = reportMapper.insertIfAbsent(
                pet.getId(), pet.getUserId(), type, period.start(), period.end(),
                current.grade(), current.avgTotal(), tierOf(), ReportPayloads.write(payload),
                AppTime.now(), TraceIds.currentOperatorId(), TraceIds.currentTraceId());
        if (written > 0) {
            prune(pet.getId(), type);
        }
        return written;
    }

    /** 保留最近 {@link #KEEP_PERIODS} 期，更老的软删除。 */
    private void prune(long petId, int type) {
        List<Long> ids = reportMapper.selectIdsDesc(petId, type);
        if (ids.size() <= KEEP_PERIODS) {
            return;
        }
        for (Long id : ids.subList(KEEP_PERIODS, ids.size())) {
            reportMapper.deleteById(id);
        }
    }

    /**
     * 拼接报告正文：**固定四段**（① 评分与趋势 ② 异常与亮点 ③ 打卡完成度 ④ 建议清单）。
     *
     * <p>段的顺序与「哪段需要权益」都是产品口径（ADR-0031 决定四），所以在这里决定，
     * 前端只负责按段渲染——顺序不该由每个端自己排。
     */
    private HealthReportPayload buildPayload(Pet pet, Period period, Window current, Window previous,
                                             LocalDate today) {
        CareMode care = careModeService.of(pet, today);
        List<HealthReportPayload.Section> sections = new ArrayList<>();
        sections.add(scoreAndTrend(current, previous));
        sections.add(abnormalAndHighlights(pet, current, previous));
        sections.add(checkinCompletion(current));
        sections.add(suggestions(pet, care, current, previous, today));
        return new HealthReportPayload(pet.getName() + "的" + period.typeName(),
                sections, statsOf(current, previous), NOTICE);
    }

    /**
     * 第 ① 段：评分与趋势。这一段**不要权益**（构造器的 {@code false}），缺数据时直说缺数据。
     *
     * <p>{@code null} 的维度直接不出行，**不是 0 分**：这一期没记行为，不能写成「行为 0 分」——
     * 那会把「没记录」读成「退步」，而报告的建议清单正是按「记录不全」解释低分的。
     */
    private HealthReportPayload.Section scoreAndTrend(Window current, Window previous) {
        List<String> lines = new ArrayList<>();
        if (current.avgTotal() == null) {
            lines.add("这一期还没有评分——一条记录都没有，评分从有记录那天开始算。");
        } else {
            lines.add("平均 " + current.avgTotal() + " 分（" + HealthScoreService.gradeOf(current.avgTotal()) + "）"
                    + comparisonOf(current.avgTotal(), previous.avgTotal(), "分"));
            if (current.avgBehavior() != null) {
                lines.add("行为维平均 " + current.avgBehavior() + " 分" + dimensionHint(current.avgBehavior()));
            }
            if (current.avgHygiene() != null) {
                lines.add("卫生维平均 " + current.avgHygiene() + " 分" + dimensionHint(current.avgHygiene()));
            }
        }
        if (current.weightMin() == null) {
            lines.add("这一期没有体重记录，趋势看不出变化。");
        } else if (current.weightMin().compareTo(current.weightMax()) == 0) {
            lines.add("体重记录为 " + current.weightMin().toPlainString() + " kg。");
        } else {
            lines.add("体重在 " + current.weightMin().toPlainString() + "–"
                    + current.weightMax().toPlainString() + " kg 之间。");
        }
        return new HealthReportPayload.Section("score_and_trend", "评分与趋势", lines, false);
    }

    /**
     * 第 ② 段：异常与亮点。
     *
     * <p>「亮点」的定义是**这一期记录的天数比上一期多**，不是分数变高：分数主要由记录完整度决定，
     * 拿分数当亮点会奖励「少记录」。
     *
     * <p>这一段带完整版标记（构造器的 {@code true}）：内容照常返回，前端据此标解锁（ADR-0031 决定四）。
     */
    private HealthReportPayload.Section abnormalAndHighlights(Pet pet, Window current, Window previous) {
        List<String> lines = new ArrayList<>();
        if (current.abnormalCount() == 0) {
            lines.add("这一期没有标注异常。");
        } else {
            lines.add("标注异常 " + current.abnormalCount() + " 次"
                    + (current.abnormalByCategory().isEmpty() ? "" : "（" + current.abnormalByCategory() + "）")
                    + comparison(current.abnormalCount(), previous.abnormalCount(), "次"));
        }
        int diffDays = current.recordDays() - previous.recordDays();
        if (previous.recordDays() == 0) {
            lines.add("记录 " + current.recordDays() + " 天，上一期没有记录可比。");
        } else if (diffDays > 0) {
            lines.add("记录 " + current.recordDays() + " 天，比上一期多 " + diffDays + " 天——这就是这一期的亮点。");
        } else if (diffDays == 0) {
            lines.add("记录 " + current.recordDays() + " 天，与上一期持平。");
        } else {
            lines.add("记录 " + current.recordDays() + " 天，比上一期少 " + (-diffDays) + " 天。");
        }
        if (current.aiConsults() > 0) {
            lines.add("向 AI 管家咨询 " + current.aiConsults() + " 次"
                    + (current.aiRedFlags() > 0 ? "，其中 " + current.aiRedFlags() + " 次被判为需要立即就医" : "")
                    + "。");
        }
        if (current.providerRecords() > 0) {
            lines.add("有 " + current.providerRecords() + " 条服务者报工记录进入档案。");
        } else {
            lines.add("这一期没有到店服务记录。");
        }
        return new HealthReportPayload.Section("abnormal_and_highlights", "异常与亮点", lines, true);
    }

    /**
     * 第 ③ 段：打卡完成度。
     *
     * <p>天数按**去重后的业务日期**算（{@code Window.recordDays} 是日期集合的大小）：一天补录三条
     * 也只算一天，否则完成度能被补录刷出来。分母是这一期的自然天数，不是「到今天为止」。
     */
    private HealthReportPayload.Section checkinCompletion(Window current) {
        List<String> lines = new ArrayList<>();
        lines.add("有记录 " + current.recordDays() + " / " + current.periodDays() + " 天。");
        int missing = current.periodDays() - current.recordDays();
        if (missing == 0) {
            lines.add("这一期每一天都有记录。");
        } else {
            lines.add("还差 " + missing + " 天没有记录；补录窗口只有 7 天，过了就只能从当天记起。");
        }
        return new HealthReportPayload.Section("checkin_completion", "打卡完成度", lines, false);
    }

    /**
     * 建议清单：**全是行动项，不是医学判断**。
     *
     * <p>措辞纪律（CONTEXT.md 的档案口径 + 交付文档 9.5）：只说「去做什么」，
     * 不说「可能是什么病」，也不给用药与剂量。
     */
    private HealthReportPayload.Section suggestions(Pet pet, CareMode care, Window current,
                                                    Window previous, LocalDate today) {
        List<String> lines = new ArrayList<>();
        if (current.recordDays() == 0) {
            lines.add("这一期没有记录：先从每天记一次六项任务开始，一周后就能看到趋势。");
        } else if (current.recordDays() * 2 < current.periodDays()) {
            lines.add("记录天数还不到一半：把每天的体重、饮食、排泄记上，评分与趋势才有依据。");
        }
        if (care.active()) {
            lines.add("处于专项照护模式：按周期复查，并把复查与用药情况记到「老年专项」分项里。");
        }
        if (current.hasDueSoon()) {
            lines.add("有疫苗或驱虫记录临近（或已过）应接种日，尽快安排。");
        }
        if (current.abnormalCount() > previous.abnormalCount() && current.abnormalCount() > 0) {
            lines.add("异常次数比上一期多：如果情况持续或加重，尽快咨询兽医。");
        }
        if (current.avgTotal() != null && previous.avgTotal() != null
                && current.avgTotal() < previous.avgTotal()) {
            lines.add("总分比上一期低：多半与记录不完整有关，记录补上分数会回来。");
        }
        if (lines.isEmpty()) {
            lines.add("这一期没有需要额外注意的事，按现在的节奏继续记录就好。");
        }
        return new HealthReportPayload.Section("suggestions", "建议清单", lines, true);
    }

    /** 契约里的结构化统计（{@code HealthReportStats}，前端画趋势用）。与正文同出一份 {@link Window}：
     *  同一个数不能在正文与 stats 里各算一遍，否则同一期会给出两个不一样的数。 */
    private HealthReportStats statsOf(Window current, Window previous) {
        return new HealthReportStats(current.recordDays(), current.periodDays(), current.abnormalCount(),
                current.avgTotal(), current.avgBehavior(), current.avgHygiene(),
                previous.avgTotal(), previous.abnormalCount(), current.weightMin(), current.weightMax(),
                current.aiConsults(), current.aiRedFlags(), current.providerRecords());
    }

    /** 写进报告行的档位。恒为完整版，但**这是个随报告落库的快照**：以后真要按权益收窄，
     *  改这里也只影响新生成的期，历史期仍是当时写下的那个数（幂等生成不回改历史）。 */
    private int tierOf() {
        // 有完整版段落（异常与亮点、建议清单）就是完整版；两种报告都含完整版内容，
        // 所以恒为 2——留给 #80 的是「解不解锁」，不是「有没有」
        return HealthReport.TIER_FULL;
    }

    /** 与上一期比较的中文尾巴（拼在数值后面）。差为 0 单独说「持平」：「高 0 次」会被读成有变化。 */
    private String comparison(int now, int before, String unit) {
        int diff = now - before;
        if (diff == 0) {
            return "，与上一期持平";
        }
        return "，比上一期" + (diff > 0 ? "高 " : "低 ") + Math.abs(diff) + " " + unit;
    }

    /** 与上一期对比的文案。**重载要改名字**：{@code (int,int,String)} 与 {@code (Integer,Integer,String)}
     *  同存时，调用方传 Integer 会有歧义（编译期就报 ambiguous，不是运行期）。 */
    private String comparisonOf(Integer now, Integer before, String unit) {
        return before == null ? "，上一期没有数据可比" : comparison(now, before.intValue(), unit);
    }

    /** 行为/卫生维的括号补充：低于 70 一律归因「记录天数偏少」——这是产品的解释口径，不是医学判断，
     *  别改成「可能有问题」那种读法（措辞纪律见 {@link #suggestions}）。 */
    private String dimensionHint(int score) {
        return score >= 70 ? "（记录比较连续）" : "（记录天数偏少，这是分数不高的主要原因）";
    }

    // ---------------------------------------------------------------- 周期与聚合

    /** 一个报告周期。 */
    private record Period(int type, String typeName, LocalDate start, LocalDate end) {

        long days() {
            return ChronoUnit.DAYS.between(start, end) + 1;
        }
    }

    /**
     * 上一个完整周期。
     *
     * <p>周报按 ISO 周（周一起点）取上周；月报取上一个自然月。**不取「本期截至今天」**：
     * 还在走的周期里完成度每天都在变，用户没法拿它跟上一期比（ADR-0031 决定一）。
     */
    static Period periodOf(int type, LocalDate today) {
        if (type == HealthReport.TYPE_WEEKLY) {
            LocalDate monday = today.with(DayOfWeek.MONDAY);
            LocalDate end = monday.minusDays(1);
            return new Period(type, "健康周报", end.minusDays(6), end);
        }
        LocalDate firstOfMonth = today.withDayOfMonth(1);
        LocalDate end = firstOfMonth.minusDays(1);
        return new Period(type, "健康月报", end.withDayOfMonth(1), end);
    }

    /** 一个窗口里、算报告需要的全部数字（两次查询 + 一次接口调用，不解析任何 JSON）。 */
    private record Window(
            int periodDays,
            int recordDays,
            int abnormalCount,
            String abnormalByCategory,
            Integer avgTotal,
            Integer avgBehavior,
            Integer avgHygiene,
            BigDecimal weightMin,
            BigDecimal weightMax,
            int aiConsults,
            int aiRedFlags,
            int providerRecords,
            boolean dueSoon,
            String grade) {

        /** 周期内是否出现「临近或已过应接种日」的防疫记录（建议清单用）。 */
        boolean hasDueSoon() {
            return dueSoon;
        }
    }

    /**
     * 聚合一期的全部数字：评分与档案记录两次查询，加 AI 统计一次跨模块调用（不解析任何 JSON）。
     *
     * <p>四个口径写在实现里，改之前先想清楚：
     *
     * <ul>
     *   <li>「异常次数」只数六个打卡维度（体重到卫生），就医/证件/老年专项的异常标记不计入；
     *   <li>有记录天数按业务日期去重，一天多条算一天；
     *   <li>「临近到期」按**周期结束日** + {@link #DUE_SOON_DAYS} 天判，不看今天——同一期重算结果不变，
     *       报告才是快照（ADR-0031 决定三）；
     *   <li>AI 的次数只经 {@link AiConsultStatsApi} 取，不 join {@code ai_consult}（ADR-0006）；
     *       报告只要两个数，所以是接口而不是第二个「读表例外」。
     * </ul>
     */
    private Window windowOf(long petId, LocalDate start, LocalDate end) {
        List<HealthScore> scores = scoreMapper.selectList(Wrappers.<HealthScore>lambdaQuery()
                .eq(HealthScore::getPetId, petId)
                .between(HealthScore::getCalcDate, start, end));
        List<ArchiveRecord> records = recordMapper.selectList(Wrappers.<ArchiveRecord>lambdaQuery()
                .eq(ArchiveRecord::getPetId, petId)
                .between(ArchiveRecord::getRecordDate, start, end));

        List<Integer> totals = new ArrayList<>();
        List<Integer> behaviors = new ArrayList<>();
        List<Integer> hygienes = new ArrayList<>();
        for (HealthScore score : scores) {
            if (score.getTotalScore() != null) {
                totals.add(score.getTotalScore());
            }
            if (score.getBehavior() != null) {
                behaviors.add(score.getBehavior());
            }
            if (score.getHygiene() != null) {
                hygienes.add(score.getHygiene());
            }
        }

        java.util.Set<LocalDate> days = new java.util.HashSet<>();
        Map<Integer, Integer> abnormalByCategory = new LinkedHashMap<>();
        BigDecimal weightMin = null;
        BigDecimal weightMax = null;
        int providerRecords = 0;
        boolean dueSoon = false;
        for (ArchiveRecord record : records) {
            days.add(record.getRecordDate());
            if (record.isAbnormalFlag() && record.getCategory() >= ArchiveRecord.CATEGORY_WEIGHT
                    && record.getCategory() <= ArchiveRecord.CATEGORY_HYGIENE) {
                abnormalByCategory.merge(record.getCategory(), 1, Integer::sum);
            }
            if (record.getCategory() == ArchiveRecord.CATEGORY_WEIGHT && record.getNumericValue() != null) {
                BigDecimal value = record.getNumericValue();
                weightMin = weightMin == null || value.compareTo(weightMin) < 0 ? value : weightMin;
                weightMax = weightMax == null || value.compareTo(weightMax) > 0 ? value : weightMax;
            }
            if (record.getSource() != null && record.getSource() == ArchiveRecord.SOURCE_PROVIDER) {
                providerRecords++;
            }
            if (record.getCategory() == ArchiveRecord.CATEGORY_EPIDEMIC && record.getDueOn() != null
                    && !record.getDueOn().isAfter(end.plusDays(DUE_SOON_DAYS))) {
                dueSoon = true;
            }
        }

        AiConsultStatsApi.ConsultStats ai = aiStats.summaryOf(petId, start, end);
        Integer avgTotal = average(totals);
        return new Window((int) (ChronoUnit.DAYS.between(start, end) + 1), days.size(),
                abnormalByCategory.values().stream().mapToInt(Integer::intValue).sum(),
                abnormalText(abnormalByCategory), avgTotal, average(behaviors), average(hygienes),
                weightMin, weightMax, ai.total(), ai.redCount(), providerRecords, dueSoon,
                // 没有数据时档位是 null（契约里写的就是这样）：`gradeOf(null)` 会给出「暂无数据」，
                // 那是**评分卡**的展示口径，不是报告字段——报告用 total_score=null 表达「这一期没有评分」
                avgTotal == null ? null : HealthScoreService.gradeOf(avgTotal));
    }

    /** 异常分类计数的摘要文案（如「体重 2 次、饮食 1 次」）。只取次数最多的前 3 类，一行放得下；
     *  **截断只影响这句话**，{@code abnormalCount} 仍是全部异常的和。 */
    private String abnormalText(Map<Integer, Integer> byCategory) {
        if (byCategory.isEmpty()) {
            return "";
        }
        Map<Integer, String> names = Map.of(
                ArchiveRecord.CATEGORY_WEIGHT, "体重", ArchiveRecord.CATEGORY_DIET, "饮食",
                ArchiveRecord.CATEGORY_EXCRETION, "排泄", ArchiveRecord.CATEGORY_BEHAVIOR, "行为",
                ArchiveRecord.CATEGORY_MOOD, "情绪", ArchiveRecord.CATEGORY_HYGIENE, "卫生");
        List<String> parts = new ArrayList<>();
        byCategory.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(3)
                .forEach(entry -> parts.add(names.getOrDefault(entry.getKey(), "其他") + " " + entry.getValue() + " 次"));
        return String.join("、", parts);
    }

    /** 一维的平均分（整数，与评分卡的显示口径一致）。**空集合返回 null 而不是 0**：没数据的维度
     *  不能被算成 0 分，否则「这一期没记」会既拖垮总分、又显示成退步。 */
    private Integer average(List<Integer> values) {
        if (values.isEmpty()) {
            return null;
        }
        int sum = 0;
        for (Integer value : values) {
            sum += value;
        }
        return (int) Math.round(sum / (double) values.size());
    }

    /** 行 → 对外视图。完整版内容已经在 payload 里，这里只把权益码与说明一起给出去——
     *  前端按「解不解锁」渲染，服务端不遮内容（ADR-0024 第一条）。 */
    private static HealthReportView toView(HealthReport report) {
        HealthReportPayload payload = ReportPayloads.read(report.getPayload());
        return new HealthReportView(report.getId(), report.getType(),
                report.getType() == HealthReport.TYPE_MONTHLY ? "健康月报" : "健康周报",
                report.getPeriodStart(), report.getPeriodEnd(), report.getGrade(),
                report.getTotalScore(), report.getTier(), PRIVILEGE_REPORT_FULL, TIER_NOTE,
                report.getGeneratedAt(), payload);
    }
}
