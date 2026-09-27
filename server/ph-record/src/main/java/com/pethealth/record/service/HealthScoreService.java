package com.pethealth.record.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pethealth.api.app.HealthScoreDimension;
import com.pethealth.api.app.HealthScoreView;
import com.pethealth.common.time.AppTime;
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

    /** 计算顺序即前端展示顺序。 */
    private static final Map<String, DimensionSpec> DIMENSIONS = new LinkedHashMap<>();

    static {
        DIMENSIONS.put("physiology", new DimensionSpec("生理",
                List.of(CATEGORY_WEIGHT, CATEGORY_DIET, CATEGORY_EXCRETION)));
        // 情绪没有独立维度，并入行为——打卡是六项、评分是五维，差额落在这里（ADR-0018）
        DIMENSIONS.put("behavior", new DimensionSpec("行为",
                List.of(CATEGORY_BEHAVIOR, CATEGORY_MOOD)));
        DIMENSIONS.put("hygiene", new DimensionSpec("卫生", List.of(CATEGORY_HYGIENE)));
        DIMENSIONS.put("epidemic", new DimensionSpec("防疫", List.of(CATEGORY_EPIDEMIC)));
        DIMENSIONS.put("elderly", new DimensionSpec("老年专项", List.of(
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

    private void upsert(long petId, LocalDate calcDate, Computed computed) {
        HealthScore existing = healthScoreMapper.selectOne(Wrappers.<HealthScore>lambdaQuery()
                .eq(HealthScore::getPetId, petId)
                .eq(HealthScore::getCalcDate, calcDate));

        HealthScore score = existing == null ? new HealthScore() : existing;
        score.setPetId(petId);
        score.setCalcDate(calcDate);
        // 没有任何记录时总分记 0：接口层用 total_score=null 表达「暂无数据」，库里不存 NULL 语义的分数
        score.setTotalScore(computed.totalScore() == null ? 0 : computed.totalScore());
        score.setPhysiology(scoreOf(computed, "physiology"));
        score.setBehavior(scoreOf(computed, "behavior"));
        score.setHygiene(scoreOf(computed, "hygiene"));
        score.setEpidemic(scoreOf(computed, "epidemic"));
        score.setElderly(scoreOf(computed, "elderly"));
        score.setIncludedDimensions(computed.includedCount());

        if (existing == null) {
            healthScoreMapper.insert(score);
        } else {
            healthScoreMapper.updateById(score);
        }
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
                empty.add(new HealthScoreDimension(entry.getKey(), entry.getValue().name(), null, false,
                        "暂无记录", "记录几天之后就会有分数"));
            }
            return new Computed(null, empty, 0);
        }

        boolean elderlyEnabled = isElderlyEnabled(pet, calcDate);
        List<HealthScoreDimension> dimensions = new ArrayList<>();
        int sum = 0;
        int included = 0;

        for (Map.Entry<String, DimensionSpec> entry : DIMENSIONS.entrySet()) {
            String key = entry.getKey();
            DimensionSpec spec = entry.getValue();

            if ("elderly".equals(key) && !elderlyEnabled) {
                dimensions.add(new HealthScoreDimension(key, spec.name(), null, false,
                        "未开启", "7 岁以上或有慢病时自动开启"));
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

            if (days == 0 && "epidemic".equals(key)) {
                // 防疫还没有录入入口（属 #102）：如实说「待录入」，而不是给 0 分让人以为疫苗出问题
                dimensions.add(new HealthScoreDimension(key, spec.name(), null, false,
                        "待录入", "录入疫苗或驱虫记录后开始计分"));
                continue;
            }

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
