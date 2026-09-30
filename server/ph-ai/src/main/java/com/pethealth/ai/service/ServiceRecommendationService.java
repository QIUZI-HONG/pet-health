package com.pethealth.ai.service;

import com.pethealth.ai.client.AiServiceClient;
import com.pethealth.ai.domain.RiskLevel;
import com.pethealth.api.app.ServiceRecommendationItem;
import com.pethealth.api.app.ServiceRecommendationMatch;
import com.pethealth.api.app.ServiceRecommendationView;
import com.pethealth.catalog.api.CatalogQueryApi;
import com.pethealth.catalog.api.Price;
import com.pethealth.catalog.api.SymptomRuleApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F011「AI 帮我找服务」的**规则版**（ADR-0050 第二节）：描述症状 → 推荐服务项目。
 *
 * <p><b>为什么要规则版而不是调模型</b>：交付文档 F011 的诉求是「描述症状 → 推荐项目」，
 * 而规则版**推荐理由说得出来**——「因为你说腹泻，建议先做常见病诊疗」里的每个字都来自数据
 * （症状词来自知识侧的受控词典、项目来自标准目录、两者的对应关系是运营可调的映射表）。
 * 模型版在知识条目还没复核完的情况下只能给出一段编出来的理由，那在医疗场景里是负价值。
 * 模型的意图路由（ADR-0025 第五节）将来是**叠加**，不是替换。
 *
 * <p><b>三段各自的归属</b>（这也是为什么它落在 ph-ai 而不是目录模块）：
 *
 * <ol>
 *   <li>症状识别在 AI 服务（只有它能读 {@code knowledge_*}，ADR-0009）；
 *   <li>症状 → 项目的映射在 ph-catalog（业务可调项，ADR-0010 的入库那一层）；
 *   <li>本类只做编排与拼装（含那句推荐理由）。
 * </ol>
 *
 * <p><b>降级不报错</b>：AI 侧读不到知识（返回 {@code knowledgeCheck=unavailable}）时，
 * 结果是空列表 + {@code degraded=true} + 一句「暂时认不出」——与其它 AI 功能同一条口径
 * （ADR-0026：AI 的降级不产生错误码）。
 */
@Service
public class ServiceRecommendationService {

    private static final Logger log = LoggerFactory.getLogger(ServiceRecommendationService.class);

    /** 就医紧迫程度的文案（与 AI 咨询同一套安全口径：红色必须说「立即就医」）。 */
    private static final String NOTICE_RED =
            "描述里出现了需要尽快处理的情况，请立即就医或联系附近的动物医院。"
                    + "以下项目只是分类参考，不能替代兽医诊断。";
    private static final String NOTICE_NORMAL =
            "以上是按你描述的症状匹配的服务项目，不是诊断。症状持续或加重请及时就医；"
                    + "具体费用以门店为准（平台不经手资金，钱在门店直接付给服务者）。";
    private static final String NOTICE_NO_SYMPTOM =
            "没有识别到具体的症状词。可以把症状说得再具体一些（例如「拉稀」「呕吐」「一直挠」），"
                    + "也可以直接按分类浏览服务目录。";
    private static final String NOTICE_DEGRADED =
            "暂时没能读出描述里的症状，请稍后再试，或直接按分类浏览服务目录。";

    private final AiServiceClient aiClient;
    private final SymptomRuleApi symptomRules;
    private final CatalogQueryApi catalogApi;

    public ServiceRecommendationService(AiServiceClient aiClient, SymptomRuleApi symptomRules,
                                        CatalogQueryApi catalogApi) {
        this.aiClient = aiClient;
        this.symptomRules = symptomRules;
        this.catalogApi = catalogApi;
    }

    @Transactional(readOnly = true)
    public ServiceRecommendationView recommend(String text) {
        AiServiceClient.SymptomMatchResponse match = aiClient.matchSymptoms(text);
        boolean degraded = !"ok".equals(match.knowledgeCheck());
        List<AiServiceClient.SymptomMatchItem> hits = match.matches();

        if (degraded) {
            return new ServiceRecommendationView(null, List.of(), List.of(), NOTICE_DEGRADED, true);
        }
        if (hits.isEmpty()) {
            return new ServiceRecommendationView(null, List.of(), List.of(), NOTICE_NO_SYMPTOM, false);
        }

        List<ServiceRecommendationMatch> matched = hits.stream()
                .map(hit -> new ServiceRecommendationMatch(hit.symptom(), hit.entryCode(), hit.title(),
                        RiskLevel.codeOfHint(hit.riskHint())))
                .toList();
        int riskLevel = matched.stream()
                .map(ServiceRecommendationMatch::riskLevel)
                .filter(level -> level != null)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);

        List<ServiceRecommendationItem> recommendations = recommendationsOf(hits);
        String notice = riskLevel == 3 ? NOTICE_RED : NOTICE_NORMAL;
        return new ServiceRecommendationView(riskLevel == 0 ? null : riskLevel, matched, recommendations, notice, false);
    }

    /**
     * 症状 → 项目。
     *
     * <p>三条口径：**保持症状顺序**（用户先说的症状先出，与词典命中顺序一致）、
     * **同一项目只出现一次**（两个症状都指向常见病诊疗时，理由取先命中的那条——
     * 重复列同一家项目会让界面看起来像出了问题）、**映射指向已删/停用的项目时跳过并记日志**
     * （运营改了目录而映射没跟上；静默跳过比把空项目名摆给用户好，但不能无声）。
     */
    private List<ServiceRecommendationItem> recommendationsOf(List<AiServiceClient.SymptomMatchItem> hits) {
        List<String> symptoms = hits.stream().map(AiServiceClient.SymptomMatchItem::symptom).distinct().toList();
        List<SymptomRuleApi.Rule> rules = symptomRules.rulesOf(symptoms);
        if (rules.isEmpty()) {
            return List.of();
        }
        Set<String> codes = new LinkedHashSet<>(rules.stream().map(SymptomRuleApi.Rule::itemCode).toList());
        Map<String, CatalogQueryApi.ItemInfo> items = catalogApi.findItems(codes);

        Map<String, ServiceRecommendationItem> byCode = new LinkedHashMap<>();
        for (String symptom : symptoms) {
            for (SymptomRuleApi.Rule rule : rules) {
                if (!rule.symptomKeyword().equals(symptom) || byCode.containsKey(rule.itemCode())) {
                    continue;
                }
                CatalogQueryApi.ItemInfo item = items.get(rule.itemCode());
                if (item == null || item.status() == null || item.status() != 1) {
                    log.warn("症状映射指向的目录项不存在或已停用，跳过这一条：symptom={} itemCode={}",
                            symptom, rule.itemCode());
                    continue;
                }
                byCode.put(rule.itemCode(), toRecommendation(item, symptom));
            }
        }
        return new ArrayList<>(byCode.values());
    }

    /** 推荐理由在这里拼：**理由要说得出「为什么是它」**，所以把症状与项目名一起说出来。 */
    private static ServiceRecommendationItem toRecommendation(CatalogQueryApi.ItemInfo item, String symptom) {
        return new ServiceRecommendationItem(
                item.code(),
                item.categoryCode(),
                item.categoryName(),
                item.name(),
                Price.format(item.priceRange().min()),
                Price.format(item.priceRange().max()),
                item.priceUnit(),
                item.durationMinutes(),
                "因为你说「" + symptom + "」，建议先做" + item.name());
    }
}
