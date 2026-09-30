package com.pethealth.record.service;

import com.pethealth.api.app.CareModeView;
import com.pethealth.record.domain.CareMode;
import com.pethealth.record.domain.CareModeRule;
import com.pethealth.record.domain.Pet;
import com.pethealth.record.mapper.CareModeRuleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 专项照护模式（切片 #116，判定与四项变化见 ADR-0032）。
 *
 * <p>这个类只做两件事：**派生判定**与**给用户看的说明**。它刻意不认识 {@code PetService}
 * （不校验归属），也不认识评分与提醒——那两处是它的调用方，反过来依赖会成环。
 * 归属校验发生在控制器与调用方（{@code PetService.requireOwned}）。
 *
 * <p>三条不变量，改动前先读 ADR：
 *
 * <ol>
 *   <li><b>派生，不落状态</b>：年龄按生日实时算、慢病按用户标记，慢病解除即退出（ADR-0024 第二节）；
 *   <li><b>判定只有一处</b>：评分、分项可见性、AI 上下文、提醒都从这里取
 *       （原先评分与提醒各写了一遍，阈值已经漂移）；
 *   <li><b>用户可以关掉它</b>，但那只落一枚意愿位（{@code pet.care_mode_disabled}），
 *       重开 = 清掉它（ADR-0032 决定一）。
 * </ol>
 */
@Service
public class CareModeService {

    /**
     * 阈值取不到时的兜底值。
     *
     * <p>与 V17 的种子数据一致（交付文档 F009 的字面「7 岁以上」）。它只在
     * {@code care_mode_rule} 被清空或读不到时生效——**业务取值在数据里**，代码里的这个数
     * 不是「配置」，是「配置缺失时的最后一道兜底」。
     */
    public static final int FALLBACK_MIN_AGE_YEARS = 7;

    /** 平台关掉该物种的自动开启时使用：一个永远达不到的阈值（慢病仍可触发）。 */
    private static final int NEVER_REACHED_YEARS = 999;

    private final CareModeRuleMapper ruleMapper;

    public CareModeService(CareModeRuleMapper ruleMapper) {
        this.ruleMapper = ruleMapper;
    }

    /** 派生某只宠物在某一天的照护状态。 */
    @Transactional(readOnly = true)
    public CareMode of(Pet pet, LocalDate today) {
        CareModeRule rule = ruleFor(pet.getSpecies());
        int threshold = rule == null ? FALLBACK_MIN_AGE_YEARS
                : (rule.isEnabled() ? value(rule) : NEVER_REACHED_YEARS);
        return CareMode.of(pet, threshold, today);
    }

    /** 该物种当前的年龄阈值（给文案用：「7 岁以上或有慢病时自动开启」）。 */
    @Transactional(readOnly = true)
    public int minAgeYears(Integer species) {
        CareModeRule rule = ruleFor(species);
        return rule == null || !rule.isEnabled() ? FALLBACK_MIN_AGE_YEARS : value(rule);
    }

    /**
     * 组装给用户看的状态。
     *
     * <p>{@code effects} 与 {@code notice} 由**后端**给：这几句是医疗相关的口径
     * （「多了哪些维度」「总分可能变化」），散落到前端各处时改起来一定会漏——
     * 与 AI 免责声明由后端给是同一个理由。
     */
    public CareModeView toView(CareMode mode, Pet pet) {
        List<CareModeView.Reason> reasons = new ArrayList<>();
        if (mode.elderly()) {
            reasons.add(new CareModeView.Reason("elderly",
                    "已进入老年期（" + (mode.ageText() == null ? "年龄已填" : mode.ageText()) + "）"));
        }
        if (mode.chronic()) {
            reasons.add(new CareModeView.Reason("chronic",
                    mode.chronicDesc() == null ? "有慢病记录" : "有慢病记录：" + mode.chronicDesc()));
        }

        List<String> effects = new ArrayList<>();
        effects.add("评分多一个「老年专项」维度，并计入总分");
        effects.add("档案里多出「老年专项」分项，用来记复查、用药与观察");
        effects.add("AI 咨询会把专项照护信息一起交给判断链路");
        effects.add("提醒按照护档走：疫苗与驱虫提前提醒、复查更密");
        if (mode.disabledByUser()) {
            effects.add("你已手动关闭照护模式：以上变化都已撤回，历史记录仍全部保留");
        }

        String notice = "照护模式会让参与总分的维度数量变化，总分可能出现波动——这是口径变化，"
                + "不是宠物变好或变差。";

        return new CareModeView(mode.active(), reasons, mode.ageText(), mode.ageYears(),
                mode.ageThreshold(), mode.elderlySince(), mode.chronicDesc(), mode.disabledByUser(),
                effects, notice);
    }

    /** 按物种取阈值行：先找该物种，再回落到通用行（{@code species=0}）。 */
    private CareModeRule ruleFor(Integer species) {
        CareModeRule common = null;
        for (CareModeRule rule : ruleMapper.selectEnabled()) {
            if (species != null && species.equals(rule.getSpecies())) {
                return rule;
            }
            if (rule.getSpecies() != null && rule.getSpecies() == CareModeRule.SPECIES_COMMON) {
                common = rule;
            }
        }
        return common;
    }

    private int value(CareModeRule rule) {
        return rule.getMinAgeYears() == null ? FALLBACK_MIN_AGE_YEARS : rule.getMinAgeYears();
    }
}
