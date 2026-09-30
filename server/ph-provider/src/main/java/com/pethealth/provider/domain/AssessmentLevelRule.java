package com.pethealth.provider.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

import java.math.BigDecimal;

/**
 * 考核等级档位（表 {@code assessment_level_rule}）：**等级本身固定三档**
 * （基础 / 优选 / 战略合作，CONTEXT.md 与 {@code provider.level} 的取值），
 * 可改的是「进入这一档的最低总分」与「对应的 AI 推荐优先级」。
 *
 * <p>{@code recommendPriority} 越小越优先（1 最高）。本切片只**存**这个映射
 * （写进考核记录与 {@code provider.level}），推荐排序本身不在本切片——见 ADR-0052 的待澄清。
 */
@TableName("assessment_level_rule")
public class AssessmentLevelRule extends BaseEntity {

    public static final int LEVEL_BASIC = 1;
    public static final int LEVEL_PREFERRED = 2;
    public static final int LEVEL_STRATEGIC = 3;

    /** 三档的固定数量：写接口要求恰好三档（缺一档就会出现「谁都不匹配」的分数段）。 */
    public static final int LEVEL_COUNT = 3;

    private Integer level;
    private String levelName;
    private BigDecimal minScore;
    private Integer recommendPriority;

    public Integer getLevel() {
        return level;
    }

    public void setLevel(Integer level) {
        this.level = level;
    }

    public String getLevelName() {
        return levelName;
    }

    public void setLevelName(String levelName) {
        this.levelName = levelName;
    }

    public BigDecimal getMinScore() {
        return minScore;
    }

    public void setMinScore(BigDecimal minScore) {
        this.minScore = minScore;
    }

    public Integer getRecommendPriority() {
        return recommendPriority;
    }

    public void setRecommendPriority(Integer recommendPriority) {
        this.recommendPriority = recommendPriority;
    }
}
