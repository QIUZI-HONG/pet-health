package com.pethealth.provider.service;

import com.pethealth.provider.domain.AssessmentItemScore;
import com.pethealth.provider.domain.AssessmentItems;
import com.pethealth.provider.domain.AssessmentLevelRule;
import com.pethealth.provider.domain.AssessmentRule;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 考核算分（**纯函数**：给定规则 + 事实 → 三项得分）。不碰数据库、不读时钟、不知道谁在调它，
 * 所以每个口径都能逐条对照着看（这也是它值得单独一个类的原因：算法与取数、落库分开，
 * 改口径时改这一处，且有测试钉着）。
 *
 * <h2>总分</h2>
 *
 * <pre>
 *   总分 = Σ(参与项得分 × 参与项权重) ÷ Σ(参与项权重)      —— 缺项按参与项**重算权重**
 *   三项：拉新 40 / 券 40 / 过程 20（权重入库可配，ADR-0039 第三节）
 * </pre>
 *
 * <h2>缺项两种口径（ADR-0050 第四节，两者的分数形状必须不同）</h2>
 *
 * <ul>
 *   <li><b>该做而没做 → 记 0 分</b>：{@code participated = true} 且 {@code score = 0.00}，
 *       明细里点名是哪一条（如「有券可出但本期一张未核销」）。
 *       典型：平台有券可出、服务者一张没核销；有订单但一单未接；
 *       平台给了拉新入口而有效邀请为 0；取消了订单（取消率 100% → 过程子项 0 分）。</li>
 *   <li><b>平台侧无该维度要求 → 不参与并重算权重</b>：{@code participated = false} 且
 *       {@code score = null}（**不是 0**），明细里注明「未参与」与原因。
 *       判据是**平台侧的事实**，一共四条（每一条都对应一个可查的数）：
 *       <ol>
 *         <li>拉新：规则里没配达标线（{@code inviteTarget = 0}）→ 平台还没定要求；
 *         <li>拉新：事实源说「平台侧还没有给这个服务者拉新入口」（{@code effectiveInvites = null}）；
 *         <li>券：规则里没配达标线（{@code couponTarget = 0.00}）；
 *         <li>券：券池里一张可贡献的服务者成本券都没有（ADR-0050 举的例子正是它）；
 *         <li>过程：期内一单都没有（没有可服务的对象，谈不上做没做）；
 *         <li>评价分：**评价体系未实现**（ADR-0039 第二节）→ 这一项本期恒不参与。
 *       </ol></li>
 *   <li>增长侧事实源**未接线**时（{@code growthWired = false}）同样按「不参与」处理，
 *       但原因写成「数据源未接线」——平台没给的数不记在服务者头上，
 *       也不能按 0 分算（那等于把一次未接线洗成一次「没做」）。</li>
 * </ul>
 *
 * <h2>三项怎么算（交付文档 F022 的权重要求 + ADR 的口径）</h2>
 *
 * <ul>
 *   <li><b>拉新</b> = 有效邀请数（ADR-0039 第一节：完成建档 + 24 小时内有行为），
 *       得分 = min(100, 有效邀请数 ÷ 达标线 × 100)；
 *   <li><b>券</b> = 贡献额度完成率 × 核销数（ADR-0039 第三节的口径原样），
 *       得分 = min(100, 该项 ÷ 达标线 × 100)。两个数一起才既惩罚「承诺了不核销」
 *       也惩罚「一张不接」——单独看完成率会奖励「零承诺」，单独看核销数会奖励「乱承诺」；
 *   <li><b>过程</b> = 五个子项（接单响应、核销率、报工完整率、评价分、服务者取消率）
 *       各 0–100 之后的**等权平均**（ADR-0039 第二节说评价分等权进过程分，子项之间同样等权；
 *       未参与的子项不占权，按参与子项重算）。五个子项的公式：
 *       <ul>
 *         <li>接单响应：平均分钟数 ≤ 达标线 → 100，否则 100 × 达标线 ÷ 平均；
 *             **有单但一单未接**（响应均值为 null 而单量 > 0）→ 0 分（该做而没做）；
 *         <li>核销率 = (履约中 + 已完成) ÷ 全部单量 × 100；
 *         <li>报工完整率 = 已完成 ÷ (履约中 + 已完成) × 100；
 *         <li>评价分：本期不参与（评价体系未实现）；
 *         <li>服务者取消率 = 服务者单方取消数 ÷ 全部单量；得分 = (1 − 取消率) × 100
 *             （ADR-0049 第七节：取消要进考核，取消越多分越低）。
 *       </ul>
 *       <p>核销率与报工完整率的分母为 0 时**不参与**而不是记 0：分母为 0 意味着「没有可算的对象」，
 *       把它记成 0 分会把「还没到那一步」和「做了但做得差」混起来。</li>
 * </ul>
 *
 * <p>所有得分保留两位小数、{@code HALF_UP}（分数不走浮点，ADR-0011 的精度纪律）。
 */
class AssessmentCalculator {

    /** 满分的上限：各项得分封顶 100，不出现「超过满分」。 */
    private static final BigDecimal FULL = BigDecimal.valueOf(100);

    /** 一个项目的计算结果。{@code score} 为 {@code null} 表示未参与（与 0 分是两件事）。 */
    record ItemResult(
            AssessmentItems.Item item,
            boolean participated,
            BigDecimal score,
            String rawValue,
            String targetValue,
            String dataSource,
            String note) {

        static ItemResult notParticipated(AssessmentItems.Item item, String dataSource, String note) {
            return new ItemResult(item, false, null, null, null, dataSource, note);
        }

        static ItemResult scored(AssessmentItems.Item item, BigDecimal score, String rawValue,
                                 String targetValue, String dataSource, String note) {
            return new ItemResult(item, true, score, rawValue, targetValue, dataSource, note);
        }
    }

    /** 一次算分的结果：三项 + 过程子项的明细、总分、参与权重合计。 */
    record Outcome(BigDecimal totalScore, int participatedWeight, List<ItemResult> items) {

        /** 取某一项的得分（未参与时为 {@code null}）。 */
        BigDecimal scoreOf(String code) {
            // 不能用 map(ItemResult::score) + findFirst：得分可能是 null（未参与），
            // 而 Stream.findFirst 内部的 Optional.of 不接受 null——会把「未参与」变成一次 NPE
            for (ItemResult result : items) {
                if (result.item().code().equals(code)) {
                    return result.score();
                }
            }
            return null;
        }

        boolean participated(String code) {
            return items.stream()
                    .anyMatch(r -> r.item().code().equals(code) && r.participated());
        }
    }

    private static final String SOURCE_ORDER = "ph-order 订单统计（OrderStatsApi）";
    private static final String SOURCE_GROWTH = "ph-privilege 有效邀请数（ProviderGrowthFactsApi）";
    private static final String SOURCE_GROWTH_COUPON = "ph-privilege 券贡献与核销统计（ProviderGrowthFactsApi）";
    private static final String SOURCE_GROWTH_UNWIRED = "ph-privilege 事实源未接线（ProviderGrowthFactsApi）";
    private static final String SOURCE_CONTENT = "ph-content 评价体系（未实现）";

    private AssessmentCalculator() {
    }

    /**
     * 算一个账期。
     *
     * @param rule 当期规则（权重与达标线，调用方负责传入**已快照**的那一份）
     * @param facts 当期事实
     */
    static Outcome evaluate(AssessmentRule rule, AssessmentFacts facts) {
        List<ItemResult> items = new ArrayList<>();
        items.add(invite(rule, facts));
        items.add(coupon(rule, facts));

        List<ItemResult> processChildren = List.of(
                responseSeconds(rule, facts),
                redeemRate(facts),
                reportRate(facts),
                reviewAbsent(),
                cancelRate(facts));
        items.add(process(rule, processChildren));
        items.addAll(processChildren);

        BigDecimal total = weightedTotal(rule, items);
        int participatedWeight = participatedWeight(rule, items);
        return new Outcome(total, participatedWeight, List.copyOf(items));
    }

    // ---------------------------------------------------------------- 三项主项

    /** 拉新 = 有效邀请数 ÷ 达标线。 */
    private static ItemResult invite(AssessmentRule rule, AssessmentFacts facts) {
        if (!facts.growthWired()) {
            return ItemResult.notParticipated(AssessmentItems.INVITE, SOURCE_GROWTH_UNWIRED,
                    "未参与：增长侧事实源未接线（ph-privilege 需实现 ProviderGrowthFactsApi），本期不计此项");
        }
        int target = rule.getInviteTarget() == null ? 0 : rule.getInviteTarget();
        if (target <= 0) {
            return ItemResult.notParticipated(AssessmentItems.INVITE, SOURCE_GROWTH,
                    "未参与：平台侧尚未配置拉新达标线（规则里的 invite_target 为 0），该项不参与并重算权重");
        }
        if (facts.effectiveInvites() == null) {
            return ItemResult.notParticipated(AssessmentItems.INVITE, SOURCE_GROWTH,
                    "未参与：平台侧还没有给这个服务者拉新入口（无可归因的邀请来源），无该维度要求");
        }
        int effective = facts.effectiveInvites();
        BigDecimal score = ratio(effective, target);
        String note = effective == 0
                ? "该做而没做：本期没有一条有效邀请，记 0 分（平台已给出拉新入口与达标线）"
                : null;
        return ItemResult.scored(AssessmentItems.INVITE, score,
                "有效邀请 " + effective + " 人", "达标线 " + target + " 人", SOURCE_GROWTH, note);
    }

    /** 券 = 完成率 × 核销数 ÷ 达标线（两个数一起看，ADR-0039 第三节）。 */
    private static ItemResult coupon(AssessmentRule rule, AssessmentFacts facts) {
        if (!facts.growthWired()) {
            return ItemResult.notParticipated(AssessmentItems.COUPON, SOURCE_GROWTH_UNWIRED,
                    "未参与：增长侧事实源未接线（ph-privilege 需实现 ProviderGrowthFactsApi），本期不计此项");
        }
        BigDecimal target = rule.getCouponTarget() == null ? AssessmentRule.UNSET_TARGET : rule.getCouponTarget();
        if (target.compareTo(BigDecimal.ZERO) <= 0) {
            return ItemResult.notParticipated(AssessmentItems.COUPON, SOURCE_GROWTH_COUPON,
                    "未参与：平台侧尚未配置券达标线（规则里的 coupon_target 为 0），该项不参与并重算权重");
        }
        if (facts.contributableTemplates() <= 0) {
            // ADR-0050 第四节举的例子：不是「没做」，是「没有可做的」
            return ItemResult.notParticipated(AssessmentItems.COUPON, SOURCE_GROWTH_COUPON,
                    "未参与：平台券池当前没有可贡献的服务者成本券，该维度对服务者无要求");
        }
        BigDecimal metric = facts.couponCompletionRate()
                .multiply(BigDecimal.valueOf(facts.couponRedeemedCount()));
        BigDecimal score = cap(metric.multiply(FULL).divide(target, 4, RoundingMode.HALF_UP));
        String raw = "完成率 " + plain(facts.couponCompletionRate()) + " × 核销 "
                + facts.couponRedeemedCount() + " 张 = " + plain(metric);
        String note = facts.couponRedeemedCount() == 0
                ? "该做而没做：平台有券可出，但本期一张都没有核销，记 0 分"
                : null;
        return ItemResult.scored(AssessmentItems.COUPON, score, raw,
                "达标线 " + plain(target), SOURCE_GROWTH_COUPON, note);
    }

    /** 过程 = 参与子项的等权平均；子项全不参与时整体不参与。 */
    private static ItemResult process(AssessmentRule rule, List<ItemResult> children) {
        List<ItemResult> participated = children.stream().filter(ItemResult::participated).toList();
        if (participated.isEmpty()) {
            return ItemResult.notParticipated(AssessmentItems.PROCESS, SOURCE_ORDER,
                    "未参与：过程分的子项本期一条都算不出来（无订单且评价体系未实现），该项不参与并重算权重");
        }
        BigDecimal sum = participated.stream()
                .map(ItemResult::score)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal score = scale(sum.divide(BigDecimal.valueOf(participated.size()), 4, RoundingMode.HALF_UP));
        return ItemResult.scored(AssessmentItems.PROCESS, score,
                "参与 " + participated.size() + " 项子项", null, SOURCE_ORDER,
                "子项之间等权（ADR-0039 第二节）；未参与的子项不占权");
    }

    // ---------------------------------------------------------------- 过程子项

    /** 接单响应：≤ 达标线记满分，超过按比例扣；有单但一单未接记 0 分。 */
    private static ItemResult responseSeconds(AssessmentRule rule, AssessmentFacts facts) {
        if (facts.orderTotal() == 0) {
            return ItemResult.notParticipated(AssessmentItems.PROCESS_RESPONSE, SOURCE_ORDER,
                    "未参与：期内没有订单，没有可响应的单");
        }
        int target = rule.getResponseMinutesTarget() == null ? 0 : rule.getResponseMinutesTarget();
        if (facts.averageResponseMinutes() == null) {
            // 有单却没有任何「已接单」时刻：这是「该做而没做」，不是「没有数据」
            return ItemResult.scored(AssessmentItems.PROCESS_RESPONSE, BigDecimal.ZERO.setScale(2),
                    "期内 " + facts.orderTotal() + " 单，一单未接", "达标线 " + target + " 分钟",
                    SOURCE_ORDER, "该做而没做：有订单但全部未接单，记 0 分");
        }
        long average = facts.averageResponseMinutes();
        BigDecimal score = average <= target
                ? FULL.setScale(2)
                : cap(BigDecimal.valueOf(100L * target).divide(BigDecimal.valueOf(average), 4, RoundingMode.HALF_UP));
        return ItemResult.scored(AssessmentItems.PROCESS_RESPONSE, score,
                "平均接单 " + average + " 分钟", "达标线 " + target + " 分钟", SOURCE_ORDER, null);
    }

    /** 核销率 = (履约中 + 已完成) ÷ 全部单量。 */
    private static ItemResult redeemRate(AssessmentFacts facts) {
        if (facts.orderTotal() == 0) {
            return ItemResult.notParticipated(AssessmentItems.PROCESS_REDEEM_RATE, SOURCE_ORDER,
                    "未参与：期内没有订单，没有可核销的对象");
        }
        BigDecimal rate = BigDecimal.valueOf(facts.redeemedOrders())
                .divide(BigDecimal.valueOf(facts.orderTotal()), 4, RoundingMode.HALF_UP);
        return ItemResult.scored(AssessmentItems.PROCESS_REDEEM_RATE, scale(rate.multiply(FULL)),
                "已核销 " + facts.redeemedOrders() + " / 订单 " + facts.orderTotal(), null,
                SOURCE_ORDER, null);
    }

    /** 报工完整率 = 已完成 ÷ (履约中 + 已完成)。 */
    private static ItemResult reportRate(AssessmentFacts facts) {
        if (facts.redeemedOrders() == 0) {
            return ItemResult.notParticipated(AssessmentItems.PROCESS_REPORT_RATE, SOURCE_ORDER,
                    "未参与：期内没有已核销的订单，分母为 0（不记 0 分：那是「还没到那一步」而不是「做了但做得差」）");
        }
        BigDecimal rate = BigDecimal.valueOf(facts.orderDone())
                .divide(BigDecimal.valueOf(facts.redeemedOrders()), 4, RoundingMode.HALF_UP);
        return ItemResult.scored(AssessmentItems.PROCESS_REPORT_RATE, scale(rate.multiply(FULL)),
                "已报工 " + facts.orderDone() + " / 已核销 " + facts.redeemedOrders(), null,
                SOURCE_ORDER, null);
    }

    /** 评价分：**本期恒不参与**（评价体系未实现，ADR-0039 第二节）。 */
    private static ItemResult reviewAbsent() {
        return ItemResult.notParticipated(AssessmentItems.PROCESS_REVIEW, SOURCE_CONTENT,
                "未参与：评价体系尚未实现（ADR-0039 第二节），本期按缺项口径处理并在明细里注明");
    }

    /** 服务者取消率 = 服务者单方取消 ÷ 全部单量；得分 = (1 − 取消率) × 100（ADR-0049 §七）。 */
    private static ItemResult cancelRate(AssessmentFacts facts) {
        if (facts.orderTotal() == 0) {
            return ItemResult.notParticipated(AssessmentItems.PROCESS_CANCEL_RATE, SOURCE_ORDER,
                    "未参与：期内没有订单，没有可取消的对象");
        }
        BigDecimal rate = BigDecimal.valueOf(facts.providerCancelled())
                .divide(BigDecimal.valueOf(facts.orderTotal()), 4, RoundingMode.HALF_UP);
        BigDecimal score = scale(BigDecimal.ONE.subtract(rate).multiply(FULL));
        return ItemResult.scored(AssessmentItems.PROCESS_CANCEL_RATE, score,
                "服务者取消 " + facts.providerCancelled() + " / 订单 " + facts.orderTotal(), null,
                SOURCE_ORDER, null);
    }

    // ---------------------------------------------------------------- 加权与工具

    /** 总分 = Σ(参与项得分 × 权重) ÷ Σ(参与项权重)；一项都没参与时给 0.00（明细里逐项写明了原因）。 */
    static BigDecimal weightedTotal(AssessmentRule rule, List<ItemResult> items) {
        BigDecimal weighted = BigDecimal.ZERO;
        int weightSum = 0;
        for (AssessmentItems.Item main : AssessmentItems.MAIN) {
            ItemResult result = find(items, main.code());
            if (result == null || !result.participated()) {
                continue;
            }
            int weight = weightOf(rule, main.code());
            weighted = weighted.add(result.score().multiply(BigDecimal.valueOf(weight)));
            weightSum += weight;
        }
        if (weightSum == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return scale(weighted.divide(BigDecimal.valueOf(weightSum), 4, RoundingMode.HALF_UP));
    }

    /** 参与计分的权重合计（缺项后小于 100；运营后台与服务者侧都用它解释总分）。 */
    static int participatedWeight(AssessmentRule rule, List<ItemResult> items) {
        int sum = 0;
        for (AssessmentItems.Item main : AssessmentItems.MAIN) {
            ItemResult result = find(items, main.code());
            if (result != null && result.participated()) {
                sum += weightOf(rule, main.code());
            }
        }
        return sum;
    }

    /** 主项权重。 */
    static int weightOf(AssessmentRule rule, String code) {
        Integer weight = switch (code) {
            case "INVITE" -> rule.getInviteWeight();
            case "COUPON" -> rule.getCouponWeight();
            case "PROCESS" -> rule.getProcessWeight();
            default -> 0;
        };
        return weight == null ? 0 : weight;
    }

    /** 按分档阈值取等级（闭区间里最高的一档）；规则不全时保守给基础档。 */
    static AssessmentLevelRule levelOf(BigDecimal total, List<AssessmentLevelRule> rules) {
        AssessmentLevelRule best = null;
        for (AssessmentLevelRule rule : rules) {
            BigDecimal min = rule.getMinScore() == null ? BigDecimal.ZERO : rule.getMinScore();
            if (total.compareTo(min) >= 0 && (best == null || min.compareTo(best.getMinScore()) > 0)) {
                best = rule;
            }
        }
        return best;
    }

    /**
     * 覆盖单项分之后的**重算**：用明细里存的**权重快照**，不读当前规则。
     *
     * <p>为什么不读当前规则：覆盖是对「这一期算出来的结果」的修订，而这一期的权重是当时快照下来的
     * （{@code assessment_item_score.weight}）。拿今天的规则去重算去年的总分，会让
     * 「跨月可对比」这件事失去意义——运营改一次权重，历史账期的分就全变了。
     */
    static BigDecimal recalcTotal(List<AssessmentItemScore> items) {
        BigDecimal weighted = BigDecimal.ZERO;
        int weightSum = 0;
        for (AssessmentItemScore item : items) {
            if (!item.hasParticipated() || item.getScore() == null || item.getParentCode() != null) {
                continue;
            }
            int weight = item.getWeight() == null ? 0 : item.getWeight();
            weighted = weighted.add(item.getScore().multiply(BigDecimal.valueOf(weight)));
            weightSum += weight;
        }
        if (weightSum == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return scale(weighted.divide(BigDecimal.valueOf(weightSum), 4, RoundingMode.HALF_UP));
    }

    /** 重算后的参与权重合计（覆盖把一项从「未参与」变成「参与」时它会变大）。 */
    static int recalcParticipatedWeight(List<AssessmentItemScore> items) {
        int sum = 0;
        for (AssessmentItemScore item : items) {
            if (item.hasParticipated() && item.getParentCode() == null) {
                sum += item.getWeight() == null ? 0 : item.getWeight();
            }
        }
        return sum;
    }

    /** 在算出来的明细里按 code 找一项，**找不到返回 null**：缺项是正常路径（事实源未接线、
     *  平台侧没配要求），调用方按「跳过」处理，别改成 {@code orElseThrow}。
     *  明细固定就几条，线性扫比建 Map 便宜。 */
    private static ItemResult find(List<ItemResult> items, String code) {
        return items.stream().filter(r -> r.item().code().equals(code)).findFirst().orElse(null);
    }

    /** 达成量 ÷ 达标线 × 100，封顶 100。 */
    private static BigDecimal ratio(long achieved, long target) {
        return cap(BigDecimal.valueOf(achieved)
                .multiply(FULL)
                .divide(BigDecimal.valueOf(target), 4, RoundingMode.HALF_UP));
    }

    /** 封顶 100 并保留两位小数。 */
    private static BigDecimal cap(BigDecimal value) {
        return scale(value.min(FULL));
    }

    /** 取整口径：保留两位、{@code HALF_UP}（分数不走浮点，ADR-0011）。{@link #cap} 与 {@link #plain}
     *  是同一条规则。中间计算刻意多留两位，出口**必须**收敛到这里——少收敛一处，库里
     *  {@code DECIMAL(5,2)} 存下的值与这里算出的值就不是一个数。 */
    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    /** 两位小数的无格式字符串（与契约里的「两位小数字符串」同一写法）。 */
    private static String plain(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
