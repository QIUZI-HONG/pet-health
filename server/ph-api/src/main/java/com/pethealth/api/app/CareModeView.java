package com.pethealth.api.app;

import java.time.LocalDate;
import java.util.List;

/**
 * 专项照护模式状态，对应 contract/app.yaml 的 {@code CareMode}（切片 #116）。
 *
 * <p><b>它是派生结果，不是存储的状态</b>：年龄按 {@code birthday} 实时算（≥ 阈值）或有慢病标记
 * 即开启，慢病解除即退出（ADR-0024 第二节 + ADR-0032）。唯一落库的是「用户手动关闭」这枚意愿位
 * （{@code disabledByUser}）——那一件事既不能从生日推导，也不能从慢病推导。
 *
 * <p>{@code effects} 由**后端**给文案：医疗相关的口径散落到前端各处一定会漏改
 * （与 AI 免责声明同一个理由）。它也是交付文档 F009「四项变化」在接口上的落点。
 */
public record CareModeView(
        boolean active,
        List<Reason> reasons,
        String ageText,
        Integer ageYears,
        int ageThresholdYears,
        LocalDate elderlySince,
        String chronicDesc,
        boolean disabledByUser,
        List<String> effects,
        String notice) {

    /** 开启原因：老年（达到年龄阈值）或慢病（用户录入的标记）。两条可能同时成立。 */
    public record Reason(String code, String label) {
    }
}
