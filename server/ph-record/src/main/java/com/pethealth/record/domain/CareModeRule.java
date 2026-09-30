package com.pethealth.record.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

/**
 * 专项照护的年龄阈值（V17，按物种一行）。
 *
 * <p>为什么入库而不是写死在代码里：[ADR-0010](../../../../../docs/adr/0010-ai-config-layering.md)
 * 要求业务可调项入库 + 运营可改，而 ADR-0024 明确记着「年龄阈值仍是占位
 * （犬 7 / 猫 10），**要兽医定稿**」。做成一行数据之后，定稿 = 一次运营改配置，不是一次发版。
 *
 * <p>{@code species} 0 是通用兜底（没有该物种的行时用它）。判定逻辑见
 * {@link CareMode#of(Pet, int, java.time.LocalDate)}。
 */
@TableName("care_mode_rule")
public class CareModeRule extends BaseEntity {

    public static final int SPECIES_COMMON = 0;

    private Integer species;
    private Integer minAgeYears;
    private Integer enabled;
    private String remark;

    public Integer getSpecies() {
        return species;
    }

    public void setSpecies(Integer species) {
        this.species = species;
    }

    public Integer getMinAgeYears() {
        return minAgeYears;
    }

    public void setMinAgeYears(Integer minAgeYears) {
        this.minAgeYears = minAgeYears;
    }

    public Integer getEnabled() {
        return enabled;
    }

    public void setEnabled(Integer enabled) {
        this.enabled = enabled;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public boolean isEnabled() {
        return enabled == null || enabled == 1;
    }
}
