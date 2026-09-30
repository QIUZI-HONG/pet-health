package com.pethealth.record.domain;

import java.time.LocalDate;
import java.time.Period;

/**
 * 专项照护状态（切片 #116，判定见 ADR-0032）。
 *
 * <p><b>它是派生结果，不是存储的状态</b>：年龄按 {@code birthday} 实时算、慢病按用户标记，
 * 慢病解除即退出（ADR-0024 第二节）。唯一落库的是「用户手动关闭」那枚意愿位——
 * 那一件事既不能从生日推导，也不能从慢病推导，所以要存（ADR-0032 决定一）。
 *
 * <p><b>为什么做成值对象而不是一堆积木式判断</b>：同一句话原先在
 * {@link com.pethealth.record.service.HealthScoreService} 与提醒模块里各写了一遍
 * （后者还把阈值硬编码成 7、不看配置），已经漂移。评分、分项可见性、AI 上下文、提醒四个地方
 * 必须从**同一处**取这个判断，否则「某处认为它是老年宠、另一处不认」这类问题只有用户投诉时才会被发现。
 *
 * @param elderly        年龄是否达到阈值（生日实时算）
 * @param chronic        是否有慢病标记（用户自述，平台不核验）
 * @param ageYears       实足年龄；没填生日为 null
 * @param ageMonths      实足月数（去掉整年后的余月，用于「X 个月」的文案）
 * @param ageThreshold   本次判定用的年龄阈值（来自 care_mode_rule，运营可调）
 * @param elderlySince   达到阈值的日期（生日 + 阈值）；没填生日为 null
 * @param chronicDesc    慢病描述（用户填的原文）
 * @param disabledByUser 用户是否手动关闭过
 */
public record CareMode(boolean elderly, boolean chronic, Integer ageYears, int ageMonths,
                       int ageThreshold, LocalDate elderlySince, String chronicDesc,
                       boolean disabledByUser) {

    /**
     * 派生判定。
     *
     * @param pet       宠物档案
     * @param threshold 年龄阈值（按物种取，见 {@code CareModeService}）
     * @param today     业务日（Asia/Shanghai）——传进来而不是在方法里取「今天」，测试才能构造历史场景
     */
    public static CareMode of(Pet pet, int threshold, LocalDate today) {
        LocalDate birthday = pet.getBirthday();
        Period age = birthday == null ? null : Period.between(birthday, today);
        Integer ageYears = age == null ? null : age.getYears();
        boolean elderly = ageYears != null && ageYears >= threshold;
        boolean disabled = pet.getCareModeDisabled() != null && pet.getCareModeDisabled() == 1;
        return new CareMode(elderly, pet.isChronicFlag(), ageYears, age == null ? 0 : age.getMonths(),
                threshold, birthday == null ? null : birthday.plusYears(threshold),
                pet.getChronicDesc(), disabled);
    }

    /** 派生事实本身是否成立（不含用户的关闭意愿）。 */
    public boolean derived() {
        return elderly || chronic;
    }

    /** 最终是否生效：派生事实 ∧ 用户没有关掉它。 */
    public boolean active() {
        return derived() && !disabledByUser;
    }

    /**
     * 给用户看的年龄文案：不足一岁说月数，满一岁说「X 岁」或「X 岁 Y 个月」。
     *
     * <p>月份数在构造时算好：值对象不该自己取当前时间——那样「按某一天的判定」就复算不出来
     * （与构造参数收 {@code today} 是同一个理由）。
     */
    public String ageText() {
        if (ageYears == null) {
            return null;
        }
        if (ageYears <= 0) {
            return ageMonths + " 个月";
        }
        return ageMonths == 0 ? ageYears + " 岁" : ageYears + " 岁 " + ageMonths + " 个月";
    }
}
