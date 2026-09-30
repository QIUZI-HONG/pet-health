package com.pethealth.ai.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pethealth.ai.domain.AiConsult;
import com.pethealth.ai.domain.AiOpsTables;
import com.pethealth.ai.mapper.AiConsultMapper;
import com.pethealth.ai.mapper.AiOpsMappers;
import com.pethealth.api.admin.AiOpsDtos;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.error.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethealth.common.util.JsonFields;
import com.pethealth.common.util.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * AI 运营配置的读写（切片 #103；分层见 ADR-0010 的「业务可调项」那一层）。
 *
 * <p>三条贯穿这个类的约定：
 *
 * <ol>
 *   <li><b>写接口不重启任何进程就生效</b>：Python 侧带 TTL 缓存直读这几张表
 *       （`ai/app/ops.py`），所以这里只管改库。**代价**是运营改完最多滞后一个 TTL
 *       （技术参数，默认 60 秒）——这一点必须让运营知道，别以为「点了没反应」。
 *   <li><b>版本号只能新增，不能原地改</b>：提示词与红线词的留痕归因都靠版本/编号
 *       （ADR-0010）。所以「新建」与「调整启用状态/灰度」是两种接口，后者才允许原子改。
 *   <li><b>`enabled` 与 `reviewStatus` 分开</b>：内容有没有被兽医复核（reviewStatus）与
 *       管线要不要用它（enabled）是两件事——词表首版就是「全部 pending_review 但 enabled」
 *       （ADR-0021）。**这一层不提供改 reviewStatus 的接口**：复核是人工流程，
 *       代码里给它开个后门等于假造复核状态（ADR-0040 第二节）。
 * </ol>
 */
@Service
public class AiOpsService {

    /** 抽检接口一次最多回多少条：与分页上限一致，防止有人拿它当全量导出。 */
    private static final long MAX_PAGE_SIZE = PageResult.MAX_PAGE_SIZE;

    private final AiOpsMappers.PromptTemplateMapper promptMapper;
    private final AiOpsMappers.GradingRuleMapper ruleMapper;
    private final AiOpsMappers.GuardTermMapper guardTermMapper;
    private final AiOpsMappers.SwitchMapper switchMapper;
    private final AiOpsMappers.RedFlagMapper redFlagMapper;
    private final AiConsultMapper consultMapper;

    public AiOpsService(AiOpsMappers.PromptTemplateMapper promptMapper,
                        AiOpsMappers.GradingRuleMapper ruleMapper,
                        AiOpsMappers.GuardTermMapper guardTermMapper,
                        AiOpsMappers.SwitchMapper switchMapper,
                        AiOpsMappers.RedFlagMapper redFlagMapper,
                        AiConsultMapper consultMapper) {
        this.promptMapper = promptMapper;
        this.ruleMapper = ruleMapper;
        this.guardTermMapper = guardTermMapper;
        this.switchMapper = switchMapper;
        this.redFlagMapper = redFlagMapper;
        this.consultMapper = consultMapper;
    }

    // ---------------------------------------------------------------- 提示词

    /** 提示词版本列表（按版本号倒序，新的在前）。 */
    public List<AiOpsDtos.PromptTemplateView> listPrompts(String code) {
        List<AiOpsTables.PromptTemplate> rows = promptMapper.selectList(
                Wrappers.<AiOpsTables.PromptTemplate>lambdaQuery()
                        .eq(Text.trimToNull(code) != null, AiOpsTables.PromptTemplate::getCode,
                                Text.trimToNull(code))
                        .orderByDesc(AiOpsTables.PromptTemplate::getVersion));
        return rows.stream().map(AiOpsService::toPromptView).toList();
    }

    /**
     * 新建一个提示词版本。
     *
     * <p>同一 (code, version) 已存在时返回 40900：**提示词是留痕的对照物**，
     * 同版本号覆盖旧内容会让「按 prompt_version 归因分级漂移」这件事失去依据（ADR-0010）。
     *
     * <p>{@code code} **没有默认值回落**：缺了它请求根本到不了这里（DTO 上的 {@code @NotBlank}
     * 先拦成 40001），那个「不传就用 triage」的分支是不可达代码。默认值这件事由契约的
     * {@code required} 表达——要在服务层实现一个永远进不去的兜底，只会让下一个人以为
     * 「不传 code 也能建」。
     */
    @Transactional
    public AiOpsDtos.PromptTemplateView createPrompt(AiOpsDtos.PromptTemplateRequest request) {
        String code = request.code().trim();
        Long exists = promptMapper.selectCount(Wrappers.<AiOpsTables.PromptTemplate>lambdaQuery()
                .eq(AiOpsTables.PromptTemplate::getCode, code)
                .eq(AiOpsTables.PromptTemplate::getVersion, request.version().trim()));
        if (exists != null && exists > 0) {
            throw BusinessException.conflict(
                    "版本 " + request.version() + " 已存在：提示词改动要新增版本号，不要原地改（留痕要靠版本归因）");
        }
        AiOpsTables.PromptTemplate row = new AiOpsTables.PromptTemplate();
        row.setCode(code);
        row.setVersion(request.version().trim());
        row.setSystemPrompt(request.systemPrompt());
        row.setToolSchema(request.toolSchema());
        row.setGrayRatio(request.grayRatio());
        row.setEnabled(1);
        // 新建的提示词一律待复核：**内容权威性由兽医/运营评审产出，不由代码盖章**
        row.setReviewStatus("pending_review");
        row.setRemark(Text.trimToNull(request.remark()));
        promptMapper.insert(row);
        return toPromptView(row);
    }

    /**
     * 调整灰度比例与启停。**这是回滚动作**：出问题把比例改回 0，不发版（ADR-0010）。
     *
     * <p>不允许改正文：正文改动必须换版本号，理由同 {@link #createPrompt}。
     */
    @Transactional
    public AiOpsDtos.PromptTemplateView updatePrompt(long id, AiOpsDtos.PromptTemplateUpdateRequest request) {
        AiOpsTables.PromptTemplate row = requirePrompt(id);
        row.setGrayRatio(request.grayRatio());
        row.setEnabled(enabledFlag(request.enabled()));
        if (Text.trimToNull(request.remark()) != null) {
            row.setRemark(request.remark().trim());
        }
        promptMapper.updateById(row);
        return toPromptView(row);
    }

    private AiOpsTables.PromptTemplate requirePrompt(long id) {
        AiOpsTables.PromptTemplate row = promptMapper.selectById(id);
        if (row == null) {
            throw BusinessException.notFound("提示词版本不存在");
        }
        return row;
    }

    // ---------------------------------------------------------------- 红线词

    /**
     * 红线词分页列表，可按启用状态过滤。硬红线是安全网，运营要能一眼看全（#103）。
     *
     * <p>{@code total} 取的是 {@code Page.getTotal()}（满足条件的总数），**不是这一页的条数**——
     * 丢了它，{@code total} 恒等于 {@code min(page_size, 剩余条数)}、{@code has_more} 恒为 false，
     * 运营会以为词表只有第一页这么多条（D20 / QueryBoundary 同一族）。
     * 形态与 {@link #sampleConsults} 一致：{@code PageResult.from(page, 映射函数)}。
     */
    public PageResult<AiOpsDtos.RedFlagView> listRedFlags(Boolean enabled, long page, long pageSize) {
        Page<AiOpsTables.RedFlag> result = redFlagMapper.selectPage(pageOf(page, pageSize),
                Wrappers.<AiOpsTables.RedFlag>lambdaQuery()
                        .eq(enabled != null, AiOpsTables.RedFlag::getEnabled, enabledFlag(enabled != null && enabled))
                        .orderByAsc(AiOpsTables.RedFlag::getCode));
        return PageResult.from(result, AiOpsService::toRedFlagView);
    }

    /** 新增一条红线。编号重复直接 40900——留痕引用编号，重复会让两次命中分不开。 */
    @Transactional
    public AiOpsDtos.RedFlagView createRedFlag(AiOpsDtos.RedFlagRequest request) {
        Long exists = redFlagMapper.selectCount(Wrappers.<AiOpsTables.RedFlag>lambdaQuery()
                .eq(AiOpsTables.RedFlag::getCode, request.code().trim()));
        if (exists != null && exists > 0) {
            throw BusinessException.conflict("规则编号 " + request.code() + " 已存在");
        }
        AiOpsTables.RedFlag row = new AiOpsTables.RedFlag();
        applyRedFlag(row, request);
        redFlagMapper.insert(row);
        return toRedFlagView(row);
    }

    @Transactional
    public AiOpsDtos.RedFlagView updateRedFlag(long id, AiOpsDtos.RedFlagRequest request) {
        AiOpsTables.RedFlag row = redFlagMapper.selectById(id);
        if (row == null) {
            throw BusinessException.notFound("红线规则不存在");
        }
        applyRedFlag(row, request);
        redFlagMapper.updateById(row);
        return toRedFlagView(row);
    }

    /** 停用（软删）一条红线。**不做物理删除**：留痕里引用过它的咨询仍要能查到当时的规则。 */
    @Transactional
    public void deleteRedFlag(long id) {
        if (redFlagMapper.selectById(id) == null) {
            throw BusinessException.notFound("红线规则不存在");
        }
        redFlagMapper.deleteById(id);
    }

    // ---------------------------------------------------------------- 分级规则

    public List<AiOpsDtos.GradingRuleView> listGradingRules(Boolean enabled) {
        List<AiOpsTables.GradingRule> rows = ruleMapper.selectList(
                Wrappers.<AiOpsTables.GradingRule>lambdaQuery()
                        .eq(enabled != null, AiOpsTables.GradingRule::getEnabled, enabledFlag(enabled != null && enabled))
                        .orderByAsc(AiOpsTables.GradingRule::getCode));
        return rows.stream().map(AiOpsService::toGradingRuleView).toList();
    }

    @Transactional
    public AiOpsDtos.GradingRuleView createGradingRule(AiOpsDtos.GradingRuleRequest request) {
        Long exists = ruleMapper.selectCount(Wrappers.<AiOpsTables.GradingRule>lambdaQuery()
                .eq(AiOpsTables.GradingRule::getCode, request.code().trim()));
        if (exists != null && exists > 0) {
            throw BusinessException.conflict("规则编号 " + request.code() + " 已存在");
        }
        AiOpsTables.GradingRule row = new AiOpsTables.GradingRule();
        applyGradingRule(row, request);
        ruleMapper.insert(row);
        return toGradingRuleView(row);
    }

    @Transactional
    public AiOpsDtos.GradingRuleView updateGradingRule(long id, AiOpsDtos.GradingRuleRequest request) {
        AiOpsTables.GradingRule row = ruleMapper.selectById(id);
        if (row == null) {
            throw BusinessException.notFound("分级规则不存在");
        }
        applyGradingRule(row, request);
        ruleMapper.updateById(row);
        return toGradingRuleView(row);
    }

    // ---------------------------------------------------------------- 护栏词表

    public List<AiOpsDtos.GuardTermView> listGuardTerms(String kind) {
        List<AiOpsTables.GuardTerm> rows = guardTermMapper.selectList(
                Wrappers.<AiOpsTables.GuardTerm>lambdaQuery()
                        .eq(Text.trimToNull(kind) != null, AiOpsTables.GuardTerm::getKind,
                                Text.trimToNull(kind))
                        .orderByAsc(AiOpsTables.GuardTerm::getKind)
                        .orderByAsc(AiOpsTables.GuardTerm::getId));
        return rows.stream().map(AiOpsService::toGuardTermView).toList();
    }

    /** 加一个护栏词（药名或越界表述）。同类同词重复直接 40900（唯一键也是这个口径）。 */
    @Transactional
    public AiOpsDtos.GuardTermView createGuardTerm(AiOpsDtos.GuardTermRequest request) {
        String kind = request.kind().trim();
        String term = request.term().trim();
        Long exists = guardTermMapper.selectCount(Wrappers.<AiOpsTables.GuardTerm>lambdaQuery()
                .eq(AiOpsTables.GuardTerm::getKind, kind)
                .eq(AiOpsTables.GuardTerm::getTerm, term));
        if (exists != null && exists > 0) {
            throw BusinessException.conflict("词条「" + term + "」已在表里");
        }
        AiOpsTables.GuardTerm row = new AiOpsTables.GuardTerm();
        applyGuardTerm(row, request);
        guardTermMapper.insert(row);
        return toGuardTermView(row);
    }

    @Transactional
    public AiOpsDtos.GuardTermView updateGuardTerm(long id, AiOpsDtos.GuardTermRequest request) {
        AiOpsTables.GuardTerm row = guardTermMapper.selectById(id);
        if (row == null) {
            throw BusinessException.notFound("护栏词条不存在");
        }
        applyGuardTerm(row, request);
        guardTermMapper.updateById(row);
        return toGuardTermView(row);
    }

    // ---------------------------------------------------------------- 开关

    public List<AiOpsDtos.SwitchView> listSwitches() {
        return switchMapper.selectList(Wrappers.<AiOpsTables.Switch>lambdaQuery()
                        .orderByAsc(AiOpsTables.Switch::getId))
                .stream().map(AiOpsService::toSwitchView).toList();
    }

    /**
     * 切一个开关（**一键降级**：`force_rule_only` 打开后全量走规则通道，见 V20 的种子说明）。
     *
     * <p>只允许改已存在的 code：新增一个**没有人读**的开关会让运营以为它生效了，
     * 那是比没有开关更糟的状态——闸门这种东西，必须由代码先认它。
     */
    @Transactional
    public AiOpsDtos.SwitchView updateSwitch(String code, AiOpsDtos.SwitchUpdateRequest request) {
        AiOpsTables.Switch row = switchMapper.selectOne(Wrappers.<AiOpsTables.Switch>lambdaQuery()
                .eq(AiOpsTables.Switch::getCode, code));
        if (row == null) {
            throw BusinessException.notFound("开关不存在：" + code);
        }
        row.setEnabled(enabledFlag(request.enabled()));
        switchMapper.updateById(row);
        return toSwitchView(row);
    }

    // ---------------------------------------------------------------- 调用留痕抽检

    /**
     * 按 prompt_version 抽检留痕（#103 的「分级结果可抽检可归因」）。
     *
     * <p>典型的用法是「改了提示词之后，按新版本抽 20 条看分级分布有没有漂」——
     * 所以过滤条件就是 prompt_version（可选再按风险等级/是否降级收窄）。
     */
    public PageResult<AiOpsDtos.ConsultAuditView> sampleConsults(String promptVersion, Integer riskLevel,
                                                                 Boolean degraded, long page, long pageSize) {
        Page<AiConsult> result = consultMapper.selectPage(pageOf(page, pageSize),
                Wrappers.<AiConsult>lambdaQuery()
                        .eq(Text.trimToNull(promptVersion) != null, AiConsult::getPromptVersion,
                                Text.trimToNull(promptVersion))
                        .eq(riskLevel != null, AiConsult::getRiskLevel, riskLevel)
                        .eq(degraded != null, AiConsult::getDegraded, degraded != null && degraded ? 1 : 0)
                        .orderByDesc(AiConsult::getCreatedAt));
        return PageResult.from(result, AiOpsService::toAuditView);
    }

    // ---------------------------------------------------------------- 私有

    /** 分页参数收口：page 至少 1、pageSize 落在 {@code [1, MAX_PAGE_SIZE]}。抽检是给运营看的接口，
     *  不设上限就等于开了一条全量导出通道（{@link #MAX_PAGE_SIZE} 的那句理由）。 */
    private static <T> Page<T> pageOf(long page, long pageSize) {
        long safePage = Math.max(1, page);
        long safeSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);
        return new Page<>(safePage, safeSize);
    }

    /** 新增与修改共用的赋值点：字段口径与缺省值只在这里写一遍，两条路径不会各写一套。
     *  范围缺省 {@code all} 表示「不限物种/年龄段」；{@code variants} 为空时写 null 而不是 {@code []}——
     *  「没有变体」在库里只留一种形状。 */
    private static void applyRedFlag(AiOpsTables.RedFlag row, AiOpsDtos.RedFlagRequest request) {
        row.setCode(request.code().trim());
        row.setPattern(request.pattern().trim());
        row.setVariants(request.variants() == null || request.variants().isEmpty()
                ? null
                : JsonFields.writeQuietly(request.variants().stream().map(String::trim).toList()));
        row.setSpeciesScope(orDefault(request.speciesScope(), "all"));
        row.setAgeStageScope(orDefault(request.ageStageScope(), "all"));
        row.setLevel(request.level());
        row.setActionHint(request.actionHint().trim());
        row.setEnabled(enabledFlag(request.enabled()));
        row.setRemark(Text.trimToNull(request.remark()));
    }

    /** 分级规则的新增与修改共用赋值点。与提示词/红线词不同，分级规则**没有版本号**：
     *  留痕靠 {@code code}（唯一键），所以一次改动是对同一行的原地更新（默认值口径同 {@link #applyRedFlag}）。 */
    private static void applyGradingRule(AiOpsTables.GradingRule row, AiOpsDtos.GradingRuleRequest request) {
        row.setCode(request.code().trim());
        row.setName(request.name().trim());
        row.setMatchTerms(JsonFields.writeQuietly(request.matchTerms().stream().map(String::trim).toList()));
        row.setMinLevel(request.minLevel());
        row.setSpeciesScope(orDefault(request.speciesScope(), "all"));
        row.setAgeStageScope(orDefault(request.ageStageScope(), "all"));
        row.setAdvice(Text.trimToNull(request.advice()));
        row.setEnabled(enabledFlag(request.enabled()));
        row.setRemark(Text.trimToNull(request.remark()));
    }

    /** 护栏词条的新增与修改共用赋值点。**这里不动 {@code reviewStatus}**：新建落列默认的 pending_review，
     *  改词保持原值——复核状态只由人工评审流程写，本层不提供改它的接口（类注释第 3 条）。 */
    private static void applyGuardTerm(AiOpsTables.GuardTerm row, AiOpsDtos.GuardTermRequest request) {
        row.setKind(request.kind().trim());
        row.setTerm(request.term().trim());
        row.setNote(Text.trimToNull(request.note()));
        row.setEnabled(enabledFlag(request.enabled()));
    }

    /**
     * JSON boolean → DB tinyint（{@code enabled} 的读写形状裁定为 boolean，见 {@code AiOpsDtos}）。
     *
     * <p>列保持 tinyint 是有意的：迁移与 Python 侧直读（`ai/app/ops.py` 里 {@code row.get("enabled")}
     * 当整数用）都不动，转换只发生在 Java 侧——与 {@code Pet.is_sterilized} 的既有做法一致。
     */
    private static Integer enabledFlag(boolean enabled) {
        return enabled ? 1 : 0;
    }

    private static String orDefault(String value, String fallback) {
        String trimmed = Text.trimToNull(value);
        return trimmed == null ? fallback : trimmed;
    }

    /** 行 → 视图。DTO 是**位置参数**：同类型的相邻字段插错顺序编译期不会报错，加字段时对照 DTO 同步改。
     *  {@code grayRatio} 与 {@code enabled} 一起决定生效范围（0 = 不生效）；{@code reviewStatus} 只读回显。 */
    private static AiOpsDtos.PromptTemplateView toPromptView(AiOpsTables.PromptTemplate row) {
        return new AiOpsDtos.PromptTemplateView(
                row.getId(), row.getCode(), row.getVersion(), row.getSystemPrompt(), row.getToolSchema(),
                row.getGrayRatio(), isEnabled(row.getEnabled()), row.getReviewStatus(), row.getRemark(),
                row.getUpdatedBy(), row.getUpdatedAt());
    }

    /** 行 → 视图。{@code variants} 从 JSON 列读回（脏数据按空列表处理，见 {@link #readList}）；
     *  {@code reviewStatus} 只读——红线是安全网，复核状态不由这个接口写。 */
    private static AiOpsDtos.RedFlagView toRedFlagView(AiOpsTables.RedFlag row) {
        return new AiOpsDtos.RedFlagView(
                row.getId(), row.getCode(), row.getPattern(), readList(row.getVariants()),
                row.getSpeciesScope(), row.getAgeStageScope(), row.getLevel(), row.getActionHint(),
                isEnabled(row.getEnabled()), row.getReviewStatus(), row.getRemark(), row.getUpdatedAt());
    }

    /** 行 → 视图。{@code minLevel} 是「命中后**至少**给到这一档」（1 绿 / 2 黄 / 3 红，见 V20 的列注释），
     *  不是「等于这一档」——读到这个字段的人不要按后者去用。 */
    private static AiOpsDtos.GradingRuleView toGradingRuleView(AiOpsTables.GradingRule row) {
        return new AiOpsDtos.GradingRuleView(
                row.getId(), row.getCode(), row.getName(), readList(row.getMatchTerms()),
                row.getMinLevel(), row.getSpeciesScope(), row.getAgeStageScope(), row.getAdvice(),
                isEnabled(row.getEnabled()), row.getReviewStatus(), row.getRemark(), row.getUpdatedAt());
    }

    /** 行 → 视图。护栏词按 {@code (kind, term)} 唯一、**没有 code 与版本**：它是可原样改的运营数据，
     *  列表一屏给全（不分页，与红线的分页列表不同）。 */
    private static AiOpsDtos.GuardTermView toGuardTermView(AiOpsTables.GuardTerm row) {
        return new AiOpsDtos.GuardTermView(
                row.getId(), row.getKind(), row.getTerm(), row.getNote(),
                isEnabled(row.getEnabled()), row.getReviewStatus(), row.getUpdatedAt());
    }

    /** 行 → 视图。{@code enabled} 的库形状是 tinyint（Python 侧直读同一列），出去时转成 boolean
     *  （见 {@link #enabledFlag}）；{@code remark} 是给运营看的「打开会发生什么」。 */
    private static AiOpsDtos.SwitchView toSwitchView(AiOpsTables.Switch row) {
        return new AiOpsDtos.SwitchView(row.getId(), row.getCode(), isEnabled(row.getEnabled()),
                row.getRemark(), row.getUpdatedAt());
    }

    /**
     * 抽检视图。**不含问题原文**（{@code question_enc} 是病历口径的密文），
     * 留痕里的结构化事实已经够回答「分级有没有漂、有没有降级、用了未复核条目没有」。
     */
    private static AiOpsDtos.ConsultAuditView toAuditView(AiConsult row) {
        return new AiOpsDtos.ConsultAuditView(
                row.getId(), row.getPetId(), row.getRiskLevel(), isEnabled(row.getDegraded()),
                row.getDegradeReason(), readList(row.getRedFlagHits()), readList(row.getGuardHits()),
                readList(row.getCitations()), readList(row.getUnvettedHits()), row.getRetrievalCheck(),
                row.getModelName(), row.getModelVersion(), row.getPromptVersion(),
                row.getLatencyMs() == null ? 0 : row.getLatencyMs(), row.getCreatedAt());
    }

    private static boolean isEnabled(Integer flag) {
        return flag != null && flag == 1;
    }

    /**
     * JSON 数组列 → 列表。存量数据里可能是 null（当时没记），返回空列表而不是抛错。
     *
     * <p>用本模块自己的 ObjectMapper（同一个理由见 `JsonFields` 的注释）：读留痕列失败不该让
     * 「看一眼留痕」这件事变成 500——脏数据显示成空列表，值不值得排查由人看日志决定。
     */
    private static List<String> readList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return READ_MAPPER.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException e) {
            log.warn("留痕列不是合法的字符串数组，按空列表处理：{}", Text.truncate(json, 120));
            return List.of();
        }
    }

    private static final ObjectMapper READ_MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(AiOpsService.class);
}
