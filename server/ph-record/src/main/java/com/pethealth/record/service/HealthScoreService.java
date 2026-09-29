package com.pethealth.record.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pethealth.api.app.HealthScoreDimension;
import com.pethealth.api.app.HealthScoreView;
import com.pethealth.common.time.AppTime;
import com.pethealth.common.trace.TraceIds;
import com.pethealth.record.domain.HealthScore;
import com.pethealth.record.domain.Pet;
import com.pethealth.record.mapper.ArchiveRecordMapper;
import com.pethealth.record.mapper.HealthScoreMapper;
import com.pethealth.record.mapper.PetMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.pethealth.record.domain.ArchiveRecord.CATEGORY_BEHAVIOR;
import static com.pethealth.record.domain.ArchiveRecord.CATEGORY_DIET;
import static com.pethealth.record.domain.ArchiveRecord.CATEGORY_EPIDEMIC;
import static com.pethealth.record.domain.ArchiveRecord.CATEGORY_EXCRETION;
import static com.pethealth.record.domain.ArchiveRecord.CATEGORY_HYGIENE;
import static com.pethealth.record.domain.ArchiveRecord.CATEGORY_MOOD;
import static com.pethealth.record.domain.ArchiveRecord.CATEGORY_WEIGHT;

/**
 * 健康评分：五维与总分（切片 #97，算法见 ADR-0018）。
 *
 * <pre>
 * 每维 = 70 × 完整度 + 30 × (1 − 0.1 × 异常项数)      四舍五入，最低 0
 *   完整度 = 该维覆盖的分项在近 7 天窗口内「有记录的天数」÷ 7
 * 总分 = 已计入维度的等权平均
 * </pre>
 *
 * <p><b>这个分数是「被观察到的健康」，不是医学评估。</b> 我们没有临床参考数据（品种体重区间、
 * 指标阈值都不存在），所以分数只由「记录是否持续」与「用户自己标注的异常」构成。
 * 这也是为什么每个维度都要给出 {@code detail}——用户看到 62 分时要能立刻明白
 * 「是因为近 7 天只记录了 2 天」，而不是怀疑宠物病了。
 */
@Service
public class HealthScoreService {

    /** 评分窗口：近 7 天（含计算日）。 */
    public static final int WINDOW_DAYS = 7;

    private static final int COMPLETENESS_WEIGHT = 70;
    private static final int QUALITY_WEIGHT = 30;
    private static final double ABNORMAL_PENALTY = 0.1;

    /** 老年专项的年龄门槛（交付文档 F009：7 岁以上或录入慢病触发）。 */
    private static final int ELDERLY_AGE_YEARS = 7;
    /** 防疫维度的两个取值（ADR-0025）：在有效期内 100、已过期 60。 */
    private static final int EPIDEMIC_OK_SCORE = 100;
    private static final int EPIDEMIC_OVERDUE_SCORE = 60;

    /**
     * 两个处理方式与其余维度不同的键。
     *
     * <p>{@code elderly} 要看年龄与慢病，不满足时整维「未开启」；{@code epidemic} 的判据
     * **不在 7 天窗口里**（疫苗按年打），取的是最近一条记录的到期状态（ADR-0025）。
     * 抽成常量是因为它们原先在 {@link #compute} 里以裸字符串出现三次——
     * 拼错一个字母不会编译报错，只会让那个维度静默走到默认分支去。
     */
    private static final String KEY_ELDERLY = "elderly";
    private static final String KEY_EPIDEMIC = "epidemic";

    /** 计算顺序即前端展示顺序。 */
    private static final Map<String, DimensionSpec> DIMENSIONS = new LinkedHashMap<>();

    static {
        DIMENSIONS.put("physiology", new DimensionSpec("生理",
                List.of(CATEGORY_WEIGHT, CATEGORY_DIET, CATEGORY_EXCRETION)));
        // 情绪没有独立维度，并入行为——打卡是六项、评分是五维，差额落在这里（ADR-0018）
        DIMENSIONS.put("behavior", new DimensionSpec("行为",
                List.of(CATEGORY_BEHAVIOR, CATEGORY_MOOD)));
        DIMENSIONS.put("hygiene", new DimensionSpec("卫生", List.of(CATEGORY_HYGIENE)));
        DIMENSIONS.put(KEY_EPIDEMIC, new DimensionSpec("防疫", List.of(CATEGORY_EPIDEMIC)));
        DIMENSIONS.put(KEY_ELDERLY, new DimensionSpec("老年专项", List.of(
                CATEGORY_WEIGHT, CATEGORY_DIET, CATEGORY_EXCRETION,
                CATEGORY_BEHAVIOR, CATEGORY_MOOD, CATEGORY_HYGIENE)));
    }

    private final HealthScoreMapper healthScoreMapper;
    private final ArchiveRecordMapper recordMapper;
    private final PetMapper petMapper;

    public HealthScoreService(HealthScoreMapper healthScoreMapper,
                             ArchiveRecordMapper recordMapper,
                             PetMapper petMapper) {
        this.healthScoreMapper = healthScoreMapper;
        this.recordMapper = recordMapper;
        this.petMapper = petMapper;
    }

    /** 算一遍并返回（不落库），并带上近 7 天趋势。给查询接口用。 */
    @Transactional(readOnly = true)
    public HealthScoreView evaluate(Pet pet) {
        LocalDate today = AppTime.today();
        Computed computed = compute(pet, today);
        return new HealthScoreView(
                computed.totalScore(),
                grade(computed.totalScore()),
                computed.dimensions(),
                trend(pet.getId(), today),
                today,
                "评分基于你近 7 天的记录（记录是否持续 + 标注的异常），用于观察趋势，不能替代兽医诊断。");
    }

    /**
     * 重算并写入当日行。打卡/撤销后调用——F005 要求打卡「有反馈」，等定时批算就看不到即时变化。
     *
     * <p>按 {@code pet_id + calc_date} 唯一键更新，一天只有一行；历史行留着供趋势与周报使用。
     * 宠物已被删除时直接返回（不补历史行）。
     */
    @Transactional
    public void recalculate(long petId, LocalDate calcDate) {
        Pet pet = petMapper.selectById(petId);
        if (pet == null) {
            return;
        }
        upsert(petId, calcDate, compute(pet, calcDate));
    }

    /**
     * 写入当日行。**一条原子 SQL**（{@code ON DUPLICATE KEY UPDATE}），不做「先查再插/改」。
     *
     * <p>并发提交（双击、双标签页、打卡与防疫同时写）在旧实现下会撞 {@code uk_pet_calc_date}，
     * 而撞唯一键会把事务标记成 rollback-only——接住异常也救不回来，提交时照样 50000
     * （2026-09-28 测试报告的并发用例实测：4 个并发提交里 2 个 500）。原子 upsert 从根上避开。
     */
    private void upsert(long petId, LocalDate calcDate, Computed computed) {
        healthScoreMapper.upsertScore(
                petId,
                calcDate,
                // 没有任何记录时总分记 0：接口层用 total_score=null 表达「暂无数据」，库里不存 NULL 语义的分数
                computed.totalScore() == null ? 0 : computed.totalScore(),
                scoreOf(computed, "physiology"),
                scoreOf(computed, "behavior"),
                scoreOf(computed, "hygiene"),
                scoreOf(computed, KEY_EPIDEMIC),
                scoreOf(computed, KEY_ELDERLY),
                computed.includedCount(),
                AppTime.now(),
                TraceIds.currentOperatorId(),
                TraceIds.currentTraceId());
    }

    /**
     * 取某一维的分数。**用循环而不是 stream.findFirst()**：未计入的维度分是 null，
     * 而 findFirst() 内部用 Optional.of 包装元素，遇到 null 直接抛 NPE——
     * 那会把整个打卡事务一起回滚掉（踩过一次）。
     */
    private Integer scoreOf(Computed computed, String key) {
        for (HealthScoreDimension dimension : computed.dimensions()) {
            if (dimension.key().equals(key)) {
                return dimension.score();
            }
        }
        return null;
    }

    /**
     * 核心计算。窗口是 {@code [calcDate-6, calcDate]}。
     *
     * <p>窗口右端是计算日而不是「今天」：重算历史某天时不该把之后的记录算进来。
     */
    private Computed compute(Pet pet, LocalDate calcDate) {
        LocalDate from = calcDate.minusDays(WINDOW_DAYS - 1L);
        List<ArchiveRecordMapper.DayCategoryAggregate> rows =
                recordMapper.selectDayCategoryAggregates(pet.getId(), from, calcDate);

        // 窗口里一条记录都没有 → 五维全部「暂无记录」，总分为空。
        // 不能给「零记录的维度」打 30 分（那是「没异常」的默认值），否则新用户一建档就看到 30 分，
        // 会被读成「健康状况差」（ADR-0018：没有数据时显示「还没有评分」）。
        if (rows.isEmpty()) {
            List<HealthScoreDimension> empty = new ArrayList<>();
            for (Map.Entry<String, DimensionSpec> entry : DIMENSIONS.entrySet()) {
                // 防疫除外：它的判据不在窗口里（ADR-0025）——只有一条三个月前的疫苗记录时，
                // 窗口内确实没有记录，但「防护还在不在有效期内」是有答案的。
                if (KEY_EPIDEMIC.equals(entry.getKey())) {
                    empty.add(epidemicDimension(pet, calcDate));
                    continue;
                }
                empty.add(new HealthScoreDimension(entry.getKey(), entry.getValue().name(), null, false,
                        "暂无记录", "记录几天之后就会有分数"));
            }
            // 总分仍为空：ADR-0018 的本意是「窗口里没有记录就不要给一个总分」，
            // 否则只补录过疫苗的用户会看到一个 100 分，那比没有分更容易误导。
            return new Computed(null, empty, 0);
        }

        boolean elderlyEnabled = isElderlyEnabled(pet, calcDate);
        List<HealthScoreDimension> dimensions = new ArrayList<>();
        int sum = 0;
        int included = 0;

        for (Map.Entry<String, DimensionSpec> entry : DIMENSIONS.entrySet()) {
            String key = entry.getKey();
            DimensionSpec spec = entry.getValue();

            if (KEY_ELDERLY.equals(key) && !elderlyEnabled) {
                dimensions.add(new HealthScoreDimension(key, spec.name(), null, false,
                        "未开启", "7 岁以上或有慢病时自动开启"));
                continue;
            }

            // 防疫维度不按「近 7 天有没有录入」算（ADR-0025）：疫苗按年打，补录历史接种是最常见的
            // 建档场景。它问的是「防护还在不在有效期内」，所以取「有没有记录」+「最近一次的到期状态」。
            // **放在下面的聚合扫描之前**：原先它排在扫描之后，那两个算出来的值（daysWithRecord /
            // abnormal）随即被 continue 丢掉，白扫一遍。
            if (KEY_EPIDEMIC.equals(key)) {
                dimensions.add(epidemicDimension(pet, calcDate));
                continue;
            }

            // 完整度 = 该维覆盖的分项在窗口内出现过的**不同日期数**（并集，不是各分项天数的最大值）
            Set<LocalDate> daysWithRecord = new HashSet<>();
            int abnormal = 0;
            for (ArchiveRecordMapper.DayCategoryAggregate row : rows) {
                if (spec.categories().contains(row.category())) {
                    daysWithRecord.add(row.recordDate());
                    abnormal += row.abnormalCount();
                }
            }
            int days = daysWithRecord.size();

            int completeness = (int) Math.round(COMPLETENESS_WEIGHT * (days / (double) WINDOW_DAYS));
            int quality = (int) Math.round(QUALITY_WEIGHT * Math.max(0, 1 - ABNORMAL_PENALTY * abnormal));
            int score = Math.max(0, completeness + quality);

            dimensions.add(new HealthScoreDimension(key, spec.name(), score, true, null,
                    "近 " + WINDOW_DAYS + " 天记录 " + days + " 天"
                            + (abnormal > 0 ? "，异常 " + abnormal + " 次" : "")));
            sum += score;
            included++;
        }

        Integer total = included == 0 ? null : (int) Math.round(sum / (double) included);
        return new Computed(total, dimensions, included);
    }

    /** 老年专项触发条件（交付文档 F009）：年龄 ≥ 7 岁，或标记了慢病。 */
    private boolean isElderlyEnabled(Pet pet, LocalDate calcDate) {
        if (pet.getIsChronic() != null && pet.getIsChronic() == 1) {
            return true;
        }
        if (pet.getBirthday() == null) {
            return false;
        }
        return Period.between(pet.getBirthday(), calcDate).getYears() >= ELDERLY_AGE_YEARS;
    }

    /**
     * 防疫维度（ADR-0025）：有记录即计入，按最近一次记录的到期状态取值。
     *
     * <p>**任一条记录的到期日已过就降档**（不是「看最新那条」）：一条刚打的疫苗会把已过期的驱虫
     * 盖过去，而防护有缺口这件事不该被盖住——这是实现时被测试逼出来的口径修正，写回 ADR-0025。
     *
     * <p>未到期 100 / 有任一条过期 60 / 无记录「待录入」不计入。**没填 {@code next_due_on} 的记录不参与
     * 过期判定**：不替用户猜周期（与 ADR-0019 对提醒的口径一致——不填就不提醒，这里是不填就不扣分）。
     */
    private HealthScoreDimension epidemicDimension(Pet pet, LocalDate calcDate) {
        ArchiveRecordMapper.EpidemicSummary summary = recordMapper.selectEpidemicSummary(pet.getId());
        if (summary == null || summary.totalCount() == 0) {
            return new HealthScoreDimension("epidemic", "防疫", null, false,
                    "待录入", "录入疫苗或驱虫记录后开始计分");
        }
        LocalDate earliestDue = summary.earliestDue();
        boolean overdue = earliestDue != null && earliestDue.isBefore(calcDate);
        return new HealthScoreDimension("epidemic", "防疫",
                overdue ? EPIDEMIC_OVERDUE_SCORE : EPIDEMIC_OK_SCORE, true, null,
                overdue ? "有记录的应接种日期已过，该补打了" : "有疫苗或驱虫记录，且在有效期内");
    }

    /** 中性档位文案：不用「优秀 / 健康」这类医学化的词（ADR-0018）。 */
    private String grade(Integer total) {
        if (total == null) {
            return "暂无数据";
        }
        if (total >= 85) {
            return "良好";
        }
        if (total >= 70) {
            return "尚可";
        }
        return "需关注";
    }

    /** 近 7 天总分序列（缺失日期不出现），供首页趋势展示。 */
    private List<HealthScoreView.TrendPoint> trend(long petId, LocalDate today) {
        List<HealthScore> rows = healthScoreMapper.selectList(Wrappers.<HealthScore>lambdaQuery()
                .eq(HealthScore::getPetId, petId)
                .between(HealthScore::getCalcDate, today.minusDays(WINDOW_DAYS - 1L), today)
                .orderByAsc(HealthScore::getCalcDate));
        List<HealthScoreView.TrendPoint> points = new ArrayList<>();
        for (HealthScore row : rows) {
            points.add(new HealthScoreView.TrendPoint(row.getCalcDate(), row.getTotalScore()));
        }
        return points;
    }

    private record DimensionSpec(String name, List<Integer> categories) {
    }

    private record Computed(Integer totalScore, List<HealthScoreDimension> dimensions, int includedCount) {
    }
}
