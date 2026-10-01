package com.pethealth.api.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 运营后台的 AI 配置接口 DTO（切片 #103），与 {@code contract/admin.yaml} 对齐。
 *
 * <p>范围：提示词（版本 + 灰度）、红线词、分级规则、护栏词表、降级开关、调用留痕抽检。
 * **读接口给运营看现状，写接口是「改完即时生效」的入口**——Python 侧带 TTL 缓存直读这几张表，
 * 所以写完不需要重启任何进程（ADR-0010 的业务可调项那一层）。
 *
 * <p>为什么 DTO 放在一个文件里：它们都是**同一个后台页面的表单形状**，拆开只会让
 * 「运营能改哪些字段」看到五处去。JSON 字段名由全局 SNAKE_CASE 决定，与契约一致。
 *
 * <p><b>{@code enabled} 读写都是 JSON boolean</b>（读侧一直是 boolean，写侧此前是 0/1 整数——
 * 同一个字段两种形状，前端照契约生成的类型与运行时会错位）。落库仍是 tinyint：
 * 迁移与 Python 直读（`ai/app/ops.py`）都不动，转换在 service 里做，
 * 与 {@code Pet.is_sterilized} 的既有做法一致（{@code Boolean} 入参 → {@code Integer} 列）。
 */
public final class AiOpsDtos {

    private AiOpsDtos() {
    }

    // ---------------------------------------------------------------- 提示词

    /**
     * 提示词版本（列表项）。正文一起给：运营要能看到自己改了什么。
     *
     * <p>{@code toolSchema} **只读下发**：它与提示词同源（缺了模型不会按格式上报），但归属
     * 代码常量那一层（ADR-0010）——工具定义是代码约束出来的协议，运营改它等于改协议。
     * 下发是为了让「当前生效的正文 + 它的工具定义」能一起被看见，改动仍走发版。
     */
    public record PromptTemplateView(
            long id,
            String code,
            String version,
            String systemPrompt,
            String toolSchema,
            Integer grayRatio,
            boolean enabled,
            String reviewStatus,
            String remark,
            Long updatedBy,
            LocalDateTime updatedAt) {
    }

    /**
     * 新建提示词版本。**版本号必须换新的**（唯一键 code+version）：原地改会让留痕里
     * 「按 prompt_version 归因分级漂移」失去对照物（ADR-0010）。
     */
    public record PromptTemplateRequest(
            @NotBlank(message = "用途代码不能为空")
            @Size(max = 32, message = "用途代码最长 32 个字符")
            String code,
            @NotBlank(message = "版本号不能为空（改提示词要新增版本，不要原地改）")
            @Size(max = 32, message = "版本号最长 32 个字符")
            String version,
            @NotBlank(message = "提示词正文不能为空")
            String systemPrompt,
            @NotBlank(message = "工具定义不能为空（与提示词同源，缺了模型不会按格式上报）")
            String toolSchema,
            @NotNull(message = "灰度比例必填")
            @Min(value = 0, message = "灰度比例 0–100")
            @Max(value = 100, message = "灰度比例 0–100")
            Integer grayRatio,
            @Size(max = 256, message = "备注最长 256 个字符")
            String remark) {
    }

    /** 调整灰度 / 启停。**这是回滚动作**：出问题把比例改回 0，不发版（ADR-0010）。 */
    public record PromptTemplateUpdateRequest(
            @NotNull(message = "灰度比例必填")
            @Min(value = 0, message = "灰度比例 0–100")
            @Max(value = 100, message = "灰度比例 0–100")
            Integer grayRatio,
            @NotNull(message = "启停状态必填")
            Boolean enabled,
            @Size(max = 256, message = "备注最长 256 个字符")
            String remark) {
    }

    // ---------------------------------------------------------------- 红线词

    public record RedFlagView(
            long id,
            String code,
            String pattern,
            List<String> variants,
            String speciesScope,
            String ageStageScope,
            Integer level,
            String actionHint,
            boolean enabled,
            String reviewStatus,
            String remark,
            LocalDateTime updatedAt) {
    }

    /**
     * 新增 / 修改一条红线。{@code enabled} 与 {@code reviewStatus} 分开：**「内容是否被兽医复核」
     * 与「管线要不要用它」是两件事**——词表首版全部 pending_review 但 enabled（ADR-0021）。
     */
    public record RedFlagRequest(
            @NotBlank(message = "规则编号不能为空（留痕要引它）")
            @Size(max = 32, message = "规则编号最长 32 个字符")
            String code,
            @NotBlank(message = "主词不能为空")
            @Size(max = 64, message = "主词最长 64 个字符")
            String pattern,
            @Size(max = 20, message = "变体最多 20 条")
            List<@Size(max = 64, message = "单条变体最长 64 个字符") String> variants,
            @Pattern(regexp = "dog|cat|all", message = "适用物种只能是 dog / cat / all")
            String speciesScope,
            @Pattern(regexp = "all|puppy_kitten|adult|senior", message = "年龄段取值不合法")
            String ageStageScope,
            @NotNull(message = "命中后的风险等级必填")
            @Min(value = 2, message = "红线的等级不低于 2")
            @Max(value = 3, message = "命中后的风险等级最高 3")
            Integer level,
            @NotBlank(message = "命中后的第一句话不能为空（它是用户看到的动作）")
            @Size(max = 256, message = "动作建议最长 256 个字符")
            String actionHint,
            @NotNull(message = "启停状态必填")
            Boolean enabled,
            @Size(max = 256, message = "备注最长 256 个字符")
            String remark) {
    }

    // ---------------------------------------------------------------- 分级规则

    public record GradingRuleView(
            long id,
            String code,
            String name,
            List<String> matchTerms,
            Integer minLevel,
            String speciesScope,
            String ageStageScope,
            String advice,
            boolean enabled,
            String reviewStatus,
            String remark,
            LocalDateTime updatedAt) {
    }

    public record GradingRuleRequest(
            @NotBlank(message = "规则编号不能为空")
            @Size(max = 32, message = "规则编号最长 32 个字符")
            String code,
            @NotBlank(message = "规则名不能为空")
            @Size(max = 64, message = "规则名最长 64 个字符")
            String name,
            @NotNull(message = "命中词不能为空")
            @Size(min = 1, max = 20, message = "命中词 1–20 条")
            List<@Size(max = 32, message = "单个命中词最长 32 个字符") String> matchTerms,
            @NotNull(message = "风险下限必填")
            @Min(value = 1, message = "风险下限 1–3")
            @Max(value = 3, message = "风险下限 1–3")
            Integer minLevel,
            @Pattern(regexp = "dog|cat|all", message = "适用物种只能是 dog / cat / all")
            String speciesScope,
            @Pattern(regexp = "all|puppy_kitten|adult|senior", message = "年龄段取值不合法")
            String ageStageScope,
            @Size(max = 256, message = "建议最长 256 个字符（会进用户的照护要点，不许写药名与剂量）")
            String advice,
            @NotNull(message = "启停状态必填")
            Boolean enabled,
            @Size(max = 256, message = "备注最长 256 个字符")
            String remark) {
    }

    // ---------------------------------------------------------------- 护栏词表

    public record GuardTermView(
            long id,
            String kind,
            String term,
            String note,
            boolean enabled,
            String reviewStatus,
            LocalDateTime updatedAt) {
    }

    public record GuardTermRequest(
            @NotBlank(message = "类型必填：drug（药名）或 phrase（越界表述）")
            @Pattern(regexp = "drug|phrase", message = "类型只能是 drug（药名）或 phrase（越界表述）")
            String kind,
            @NotBlank(message = "词条不能为空")
            @Size(max = 64, message = "词条最长 64 个字符")
            String term,
            @Size(max = 256, message = "说明最长 256 个字符")
            String note,
            @NotNull(message = "启停状态必填")
            Boolean enabled) {
    }

    // ---------------------------------------------------------------- 开关

    public record SwitchView(
            long id,
            String code,
            boolean enabled,
            String remark,
            LocalDateTime updatedAt) {
    }

    /** 开关只改 enabled：**code 是代码里的常量**，运营新增一个没人读的开关只会让人误会它生效了。 */
    public record SwitchUpdateRequest(
            @NotNull(message = "开关状态必填")
            Boolean enabled) {
    }

    // ---------------------------------------------------------------- 调用留痕抽检

    /**
     * 一次咨询的抽检视图（#103 的「分级结果可抽检可归因」）。
     *
     * <p><b>不含问题原文</b>：`ai_consult.question_enc` 是字段级加密的病历口径（ADR-0013），
     * 解密给运营看属于权限与合规问题，还没定（ADR-0033 的待澄清）——先把留痕里的
     * 结构化事实给出来，够做「按 prompt_version 抽样看分级分布」这件事。
     *
     * @param unvettedHits 用到未复核条目的编号：抽检时最该看的就是这些（它们没有兽医背书）
     */
    public record ConsultAuditView(
            long id,
            long petId,
            int riskLevel,
            boolean degraded,
            String degradeReason,
            List<String> redFlagHits,
            List<String> guardHits,
            List<String> citations,
            List<String> unvettedHits,
            String retrievalCheck,
            String modelName,
            String modelVersion,
            String promptVersion,
            int latencyMs,
            LocalDateTime createdAt) {
    }

    /**
     * 知识条目（运营复核用）：只给复核要看的字段——正文与 L1 载荷不进列表（那是检索素材）。
     *
     * <p><b>类名带 `Admin` 前缀是必须的</b>：C 端另有一份同形状不同用途的 `KnowledgeEntryView`
     * （那份带正文与 `risk_level`），而契约漂移守卫要求 **schema 名 ↔ DTO 简单名一一对应**
     * （`DtoIndex` 会因为两个同简单名的 DTO 直接报错）。契约里的 schema 同名。
     *
     * <p>{@code reviewStatus} 只有两级（{@code pending_review} / {@code vetted}），
     * 而**只有 vetted 能进 citations**（ADR-0025 / ADR-0040）。
     */
    public record AdminKnowledgeEntryView(
            Long id,
            String code,
            String categoryCode,
            String title,
            String summary,
            String riskHint,
            String confidence,
            String sourceTitle,
            String sourceUrl,
            String reviewStatus,
            String reviewedBy,
            String reviewedCredential,
            LocalDateTime reviewedAt,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            LocalDateTime updatedAt) {
    }

    /**
     * 复核请求：{@code vet}（通过）或 {@code reject}（打回未复核）。
     *
     * <p>{@code reviewer} 与 {@code credential} **必填**：`vetted` 是一句专业背书，
     * 要落「谁 + 什么资质」——这是 ADR-0040 第二节「不许在代码里假造复核状态」的落地方式
     * （不是禁止人工复核，是禁止自动置位，见 ADR-0054）。
     */
    public record KnowledgeReviewRequest(
            @NotBlank(message = "复核动作不能为空（vet / reject）")
            @Pattern(regexp = "vet|reject", message = "复核动作只能是 vet 或 reject")
            String action,
            @NotBlank(message = "复核人不能为空：vetted 是一句专业背书，要落到数据里")
            @Size(max = 64, message = "复核人最长 64 个字符")
            String reviewer,
            @NotBlank(message = "复核资质不能为空（如「执业兽医师，证号 XXXX」）")
            @Size(max = 128, message = "复核资质最长 128 个字符")
            String credential) {
    }
}
