package com.pethealth.ai.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

/**
 * AI 运营可调项的四张配置表（迁移 V20）+ 红线词表（V6）+ **知识条目**（V18）的实体。
 *
 * <p>为什么把这些实体放在一个文件里：它们都是**纯配置载体**——字段、getter、setter，
 * 没有任何行为，拆成五个文件只会让「运营到底能改什么」这件事看到五处去。
 * 有行为的那部分在 {@code service/AiOpsService}。
 *
 * <p>知识条目（{@code knowledge_entry}）与那五张表的区别：它不是「调参」，是**内容**；
 * 这里的实体只映射运营复核要读写的那几列（正文与载荷不进 Java 侧），写入口只有复核那一个
 * （见 {@code AiOpsService#reviewKnowledgeEntry} 与 ADR-0054）。
 *
 * <p>命名落在 {@code knowledge_*} 下是刻意的（ADR-0033）：ADR-0009 只允许 AI 服务读
 * `knowledge_*`，而提示词/词表/开关必须在**每次咨询时**被 Python 直接读到——
 * 走 Java 接口意味着 Java 调 Python、Python 再回调 Java 取配置，多一跳还把配置可用性
 * 绑在另一个进程上。红线词表（V6）当时就是这么定的，这里沿用同一口径。
 */
public final class AiOpsTables {

    private AiOpsTables() {
    }

    /** 提示词模板：带版本号与灰度比例，改完即时生效（ADR-0010 的业务可调项）。 */
    @TableName("knowledge_prompt_template")
    public static class PromptTemplate extends BaseEntity {
        private String code;
        private String version;
        private String systemPrompt;
        /** 工具定义（JSON 文本）。与提示词同源：一个来自库、一个来自代码会出现两者不匹配的组合。 */
        private String toolSchema;
        private Integer grayRatio;
        private Integer enabled;
        private String reviewStatus;
        private String remark;

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public String getSystemPrompt() {
            return systemPrompt;
        }

        public void setSystemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
        }

        public String getToolSchema() {
            return toolSchema;
        }

        public void setToolSchema(String toolSchema) {
            this.toolSchema = toolSchema;
        }

        public Integer getGrayRatio() {
            return grayRatio;
        }

        public void setGrayRatio(Integer grayRatio) {
            this.grayRatio = grayRatio;
        }

        public Integer getEnabled() {
            return enabled;
        }

        public void setEnabled(Integer enabled) {
            this.enabled = enabled;
        }

        public String getReviewStatus() {
            return reviewStatus;
        }

        public void setReviewStatus(String reviewStatus) {
            this.reviewStatus = reviewStatus;
        }

        public String getRemark() {
            return remark;
        }

        public void setRemark(String remark) {
            this.remark = remark;
        }
    }

    /** 分级规则：命中即把风险**抬到**某一档（红线是短路，两者动作不同，所以两张表）。 */
    @TableName("knowledge_grading_rule")
    public static class GradingRule extends BaseEntity {
        private String code;
        private String name;
        /** 命中词（JSON 数组文本），任一词命中即算命中。 */
        private String matchTerms;
        private Integer minLevel;
        private String speciesScope;
        private String ageStageScope;
        private String advice;
        private Integer enabled;
        private String reviewStatus;
        private String remark;

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getMatchTerms() {
            return matchTerms;
        }

        public void setMatchTerms(String matchTerms) {
            this.matchTerms = matchTerms;
        }

        public Integer getMinLevel() {
            return minLevel;
        }

        public void setMinLevel(Integer minLevel) {
            this.minLevel = minLevel;
        }

        public String getSpeciesScope() {
            return speciesScope;
        }

        public void setSpeciesScope(String speciesScope) {
            this.speciesScope = speciesScope;
        }

        public String getAgeStageScope() {
            return ageStageScope;
        }

        public void setAgeStageScope(String ageStageScope) {
            this.ageStageScope = ageStageScope;
        }

        public String getAdvice() {
            return advice;
        }

        public void setAdvice(String advice) {
            this.advice = advice;
        }

        public Integer getEnabled() {
            return enabled;
        }

        public void setEnabled(Integer enabled) {
            this.enabled = enabled;
        }

        public String getReviewStatus() {
            return reviewStatus;
        }

        public void setReviewStatus(String reviewStatus) {
            this.reviewStatus = reviewStatus;
        }

        public String getRemark() {
            return remark;
        }

        public void setRemark(String remark) {
            this.remark = remark;
        }
    }

    /** 输出层护栏词表：药名与越界表述（ADR-0026 第三节说的「有期限的例外」的归宿）。 */
    @TableName("knowledge_guard_term")
    public static class GuardTerm extends BaseEntity {
        /** drug（药名）/ phrase（越界表述）。 */
        private String kind;
        private String term;
        private String note;
        private Integer enabled;
        private String reviewStatus;

        public String getKind() {
            return kind;
        }

        public void setKind(String kind) {
            this.kind = kind;
        }

        public String getTerm() {
            return term;
        }

        public void setTerm(String term) {
            this.term = term;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }

        public Integer getEnabled() {
            return enabled;
        }

        public void setEnabled(Integer enabled) {
            this.enabled = enabled;
        }

        public String getReviewStatus() {
            return reviewStatus;
        }

        public void setReviewStatus(String reviewStatus) {
            this.reviewStatus = reviewStatus;
        }
    }

    /**
     * 运行时开关。**语义由 code 决定，不是清一色「打开就生效」**——运营看 remark 再动手。
     */
    @TableName("knowledge_switch")
    public static class Switch extends BaseEntity {
        private String code;
        private Integer enabled;
        private String remark;

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
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
    }

    /**
     * 硬红线规则（表 {@code knowledge_red_flag}，迁移 V6）。原先是 Python 直读、没有实体；
     * 切片 #103 要求「硬红线词可增删」，读侧一直有，**写侧**（运营增删）需要它。
     */
    @TableName("knowledge_red_flag")
    public static class RedFlag extends BaseEntity {
        private String code;
        private String pattern;
        /** 同义词与口语变体（JSON 数组文本）。 */
        private String variants;
        private String speciesScope;
        private String ageStageScope;
        private Integer level;
        private String actionHint;
        private Integer enabled;
        private String reviewStatus;
        private String reviewedBy;
        private String remark;

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getPattern() {
            return pattern;
        }

        public void setPattern(String pattern) {
            this.pattern = pattern;
        }

        public String getVariants() {
            return variants;
        }

        public void setVariants(String variants) {
            this.variants = variants;
        }

        public String getSpeciesScope() {
            return speciesScope;
        }

        public void setSpeciesScope(String speciesScope) {
            this.speciesScope = speciesScope;
        }

        public String getAgeStageScope() {
            return ageStageScope;
        }

        public void setAgeStageScope(String ageStageScope) {
            this.ageStageScope = ageStageScope;
        }

        public Integer getLevel() {
            return level;
        }

        public void setLevel(Integer level) {
            this.level = level;
        }

        public String getActionHint() {
            return actionHint;
        }

        public void setActionHint(String actionHint) {
            this.actionHint = actionHint;
        }

        public Integer getEnabled() {
            return enabled;
        }

        public void setEnabled(Integer enabled) {
            this.enabled = enabled;
        }

        public String getReviewStatus() {
            return reviewStatus;
        }

        public void setReviewStatus(String reviewStatus) {
            this.reviewStatus = reviewStatus;
        }

        public String getReviewedBy() {
            return reviewedBy;
        }

        public void setReviewedBy(String reviewedBy) {
            this.reviewedBy = reviewedBy;
        }

        public String getRemark() {
            return remark;
        }

        public void setRemark(String remark) {
            this.remark = remark;
        }
    }

    /**
     * 知识条目（表 {@code knowledge_entry}，迁移 V18）。
     *
     * <p>只映射**运营复核要用的列**：正文（{@code body}）与 L1 载荷（{@code structured_payload}）
     * 不进 Java 侧——它们是 AI 服务的检索素材，本模块不该顺手把它们复制一份。
     *
     * <p>两处刻意：
     *
     * <ul>
     *   <li>{@code review_status} 只有两级（{@code pending_review} / {@code vetted}，V18 的列注释），
     *       「打回」就是置回 {@code pending_review}——不发明第三态；</li>
     *   <li>复核要落 **谁 + 什么资质 + 什么时候**（{@code reviewed_by} / {@code reviewed_credential} /
     *       {@code reviewed_at}）：`vetted` 是一句专业背书，不是一次开关操作
     *       （ADR-0040 第二节「不许在代码里假造复核状态」的落地方式见 ADR-0054）。</li>
     * </ul>
     */
    @TableName("knowledge_entry")
    public static class KnowledgeEntry extends BaseEntity {

        public static final String STATUS_PENDING = "pending_review";
        public static final String STATUS_VETTED = "vetted";

        private String code;
        private String categoryCode;
        private String title;
        private String summary;
        private String riskHint;
        private String confidence;
        private String sourceTitle;
        private String sourceUrl;
        private String reviewStatus;
        private String reviewedBy;
        private String reviewedCredential;
        private java.time.LocalDateTime reviewedAt;
        private java.time.LocalDate effectiveFrom;
        private java.time.LocalDate effectiveTo;

        /** 复核过没有。**只有它能为 true 时条目才可能进 citations**（ADR-0025 / ADR-0040）。 */
        public boolean isVetted() {
            return STATUS_VETTED.equals(reviewStatus);
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getCategoryCode() {
            return categoryCode;
        }

        public void setCategoryCode(String categoryCode) {
            this.categoryCode = categoryCode;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getSummary() {
            return summary;
        }

        public void setSummary(String summary) {
            this.summary = summary;
        }

        public String getRiskHint() {
            return riskHint;
        }

        public void setRiskHint(String riskHint) {
            this.riskHint = riskHint;
        }

        public String getConfidence() {
            return confidence;
        }

        public void setConfidence(String confidence) {
            this.confidence = confidence;
        }

        public String getSourceTitle() {
            return sourceTitle;
        }

        public void setSourceTitle(String sourceTitle) {
            this.sourceTitle = sourceTitle;
        }

        public String getSourceUrl() {
            return sourceUrl;
        }

        public void setSourceUrl(String sourceUrl) {
            this.sourceUrl = sourceUrl;
        }

        public String getReviewStatus() {
            return reviewStatus;
        }

        public void setReviewStatus(String reviewStatus) {
            this.reviewStatus = reviewStatus;
        }

        public String getReviewedBy() {
            return reviewedBy;
        }

        public void setReviewedBy(String reviewedBy) {
            this.reviewedBy = reviewedBy;
        }

        public String getReviewedCredential() {
            return reviewedCredential;
        }

        public void setReviewedCredential(String reviewedCredential) {
            this.reviewedCredential = reviewedCredential;
        }

        public java.time.LocalDateTime getReviewedAt() {
            return reviewedAt;
        }

        public void setReviewedAt(java.time.LocalDateTime reviewedAt) {
            this.reviewedAt = reviewedAt;
        }

        public java.time.LocalDate getEffectiveFrom() {
            return effectiveFrom;
        }

        public void setEffectiveFrom(java.time.LocalDate effectiveFrom) {
            this.effectiveFrom = effectiveFrom;
        }

        public java.time.LocalDate getEffectiveTo() {
            return effectiveTo;
        }

        public void setEffectiveTo(java.time.LocalDate effectiveTo) {
            this.effectiveTo = effectiveTo;
        }
    }
}
