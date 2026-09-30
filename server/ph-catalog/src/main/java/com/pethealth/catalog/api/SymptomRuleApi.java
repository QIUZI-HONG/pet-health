package com.pethealth.catalog.api;

import java.util.Collection;
import java.util.List;

/**
 * 「症状 → 目录项」映射的只读查询（跨模块）：F011 规则版的推荐依据（ADR-0050 第二节）。
 *
 * <p>为什么单独一个接口而不是塞进 {@link CatalogQueryApi}：两者的消费者与变更原因不同——
 * 那个是「目录项长什么样」（名称/区间/单位，几乎所有模块都读），这个是
 * 「症状该推荐什么」（只有 F011 的推荐链路读）。混在一起会让「加一条症状映射」变成
 * 一次全模块的接口变更评审。
 *
 * <p><b>症状词用知识侧的规范名</b>（{@code knowledge_node.name}：呕吐 / 腹泻 / …）：
 * 用户口语（「拉稀」）由 AI 侧的受控词典归一后再到这里——所以这一层不认识口语词，
 * 也不需要维护第二份口语表。
 *
 * <p>实现在 {@code com.pethealth.catalog.service.SymptomRuleService}。
 */
public interface SymptomRuleApi {

    /**
     * 一条映射：某个症状推荐某个目录项。
     *
     * @param symptomKeyword 症状规范名
     * @param itemCode       目录项编码（形如 HE-012）
     * @param sortOrder      同一症状下的展示顺序，升序（越小越先展示）
     */
    record Rule(String symptomKeyword, String itemCode, int sortOrder) {
    }

    /**
     * 按症状规范名批量取**启用中**的映射。
     *
     * @param symptomKeywords 症状名集合；空集合不查库，直接返回空列表
     * @return 按症状名与 sortOrder 升序排好的映射；查不到的症状不会出现在结果里
     *         （调用方按「这个症状暂时没有推荐项目」处理，**不要假设每个入参都有值**）
     */
    List<Rule> rulesOf(Collection<String> symptomKeywords);
}
