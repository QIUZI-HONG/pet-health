package com.pethealth.catalog.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pethealth.api.admin.SymptomRuleRequest;
import com.pethealth.api.admin.SymptomRuleView;
import com.pethealth.catalog.api.SymptomRuleApi;
import com.pethealth.catalog.domain.CatalogSymptomRule;
import com.pethealth.catalog.mapper.CatalogSymptomRuleMapper;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.util.Text;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * 「症状 → 目录项」映射的读取（F011 规则版）。
 *
 * <p>只读、只给启用中的映射：这一层不做任何匹配（匹配在 AI 侧，用受控词典），
 * 也不决定「推荐几个」——那是调用方（ph-ai 的推荐服务）的事：它按症状与 sortOrder 依次取。
 */
@Service
public class SymptomRuleService implements SymptomRuleApi {

    private final CatalogSymptomRuleMapper mapper;
    private final CatalogQueryService catalogQuery;

    public SymptomRuleService(CatalogSymptomRuleMapper mapper, CatalogQueryService catalogQuery) {
        this.mapper = mapper;
        this.catalogQuery = catalogQuery;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Rule> rulesOf(Collection<String> symptomKeywords) {
        if (symptomKeywords == null || symptomKeywords.isEmpty()) {
            // 空集合不查库（与 CatalogQueryApi.findItems 同一条取舍）：没有症状时「查一次空表」
            // 既没有意义，也会在没有任何命中的请求上白跑一次 SQL
            return List.of();
        }
        return mapper.selectList(Wrappers.<CatalogSymptomRule>lambdaQuery()
                        .in(CatalogSymptomRule::getSymptomKeyword, symptomKeywords)
                        .eq(CatalogSymptomRule::getEnabled, CatalogSymptomRule.STATUS_ENABLED))
                .stream()
                // 症状名 → sortOrder → id：同一症状下的展示顺序是运营定的（首条是主推）；
                // 再加 id 兜底，免得 sortOrder 相同的两行顺序随数据库返回顺序抖动
                .sorted(Comparator.comparing(CatalogSymptomRule::getSymptomKeyword)
                        .thenComparing(rule -> rule.getSortOrder() == null ? 0 : rule.getSortOrder())
                        .thenComparing(CatalogSymptomRule::getId))
                .map(rule -> new Rule(rule.getSymptomKeyword(), rule.getItemCode(),
                        rule.getSortOrder() == null ? 0 : rule.getSortOrder()))
                .toList();
    }

    // ---------------------------------------------------------------- 运营侧（配置 CRUD）

    /**
     * 映射列表（运营后台）：可按症状规范名精确筛，按症状名与顺序升序。
     *
     * <p>`itemName` / `categoryName` 从目录现取：运营要能一眼看出某条映射指向的项目是否还在、
     * 是否已被停用——**那正是这条映射即将失效的信号**。
     */
    @Transactional(readOnly = true)
    public PageResult<SymptomRuleView> page(String symptomKeyword, long page, long pageSize) {
        String keyword = Text.trimToNull(symptomKeyword);
        Page<CatalogSymptomRule> result = mapper.selectPage(new Page<>(page, pageSize),
                Wrappers.<CatalogSymptomRule>lambdaQuery()
                        .eq(keyword != null, CatalogSymptomRule::getSymptomKeyword, keyword)
                        .orderByAsc(CatalogSymptomRule::getSymptomKeyword)
                        .orderByAsc(CatalogSymptomRule::getSortOrder)
                        .orderByAsc(CatalogSymptomRule::getId));
        return PageResult.from(result, this::toView);
    }

    /**
     * 新增一条映射。
     *
     * <p>两道校验都是「早失败比躺着好」：**项目必须存在且启用**（指向不存在/停用的项目，
     * 推荐时会被跳过——那是一条永远不会生效的配置），**同一（症状，项目）只能有一条**
     * （唯一键兜底，这里先查一次是为了给出人话的 40900，而不是数据库的约束异常）。
     */
    @Transactional
    public SymptomRuleView create(SymptomRuleRequest request) {
        String symptom = request.symptomKeyword().trim();
        String itemCode = request.itemCode().trim();
        requireEnabledItem(itemCode);

        CatalogSymptomRule existing = mapper.selectOne(Wrappers.<CatalogSymptomRule>lambdaQuery()
                .eq(CatalogSymptomRule::getSymptomKeyword, symptom)
                .eq(CatalogSymptomRule::getItemCode, itemCode));
        if (existing != null) {
            throw BusinessException.conflict("这条映射已经存在（同一症状下同一个项目只能配一次）");
        }

        CatalogSymptomRule rule = new CatalogSymptomRule();
        rule.setSymptomKeyword(symptom);
        rule.setItemCode(itemCode);
        rule.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        rule.setEnabled(request.enabled() == null ? CatalogSymptomRule.STATUS_ENABLED : request.enabled());
        mapper.insert(rule);
        return toView(rule);
    }

    /** 改一条映射（顺序 / 项目 / 启停）。改项目时同样要校验它存在且启用。 */
    @Transactional
    public SymptomRuleView update(long ruleId, SymptomRuleRequest request) {
        CatalogSymptomRule rule = mapper.selectById(ruleId);
        if (rule == null) {
            throw BusinessException.notFound("映射不存在：" + ruleId);
        }
        String symptom = request.symptomKeyword().trim();
        String itemCode = request.itemCode().trim();
        requireEnabledItem(itemCode);

        boolean samePair = symptom.equals(rule.getSymptomKeyword()) && itemCode.equals(rule.getItemCode());
        if (!samePair) {
            CatalogSymptomRule conflict = mapper.selectOne(Wrappers.<CatalogSymptomRule>lambdaQuery()
                    .eq(CatalogSymptomRule::getSymptomKeyword, symptom)
                    .eq(CatalogSymptomRule::getItemCode, itemCode));
            if (conflict != null) {
                throw BusinessException.conflict("这条映射已经存在（同一症状下同一个项目只能配一次）");
            }
        }
        rule.setSymptomKeyword(symptom);
        rule.setItemCode(itemCode);
        rule.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        rule.setEnabled(request.enabled() == null ? CatalogSymptomRule.STATUS_ENABLED : request.enabled());
        mapper.updateById(rule);
        return toView(rule);
    }

    /** 项目必须是**存在且启用**的目录项：指向停用项的映射永远不会生效，不如现在就拒绝。 */
    private void requireEnabledItem(String itemCode) {
        com.pethealth.catalog.api.CatalogQueryApi.ItemInfo item = catalogQuery.findItem(itemCode).orElse(null);
        if (item == null) {
            throw BusinessException.paramInvalid("项目编码不存在：" + itemCode);
        }
        if (item.status() == null || item.status() != 1) {
            throw BusinessException.paramInvalid("该项目已停用，不能作为推荐目标：" + itemCode);
        }
    }

    private SymptomRuleView toView(CatalogSymptomRule rule) {
        com.pethealth.catalog.api.CatalogQueryApi.ItemInfo item = catalogQuery.findItem(rule.getItemCode()).orElse(null);
        return new SymptomRuleView(
                rule.getId(),
                rule.getSymptomKeyword(),
                rule.getItemCode(),
                item == null ? null : item.name(),
                item == null ? null : item.categoryName(),
                rule.getSortOrder(),
                rule.getEnabled(),
                rule.getUpdatedAt());
    }
}
