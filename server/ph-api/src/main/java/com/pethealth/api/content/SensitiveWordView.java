package com.pethealth.api.content;

import java.time.LocalDateTime;

/**
 * 机审词表里的一条，对应 contract/admin.yaml 的 {@code SensitiveWordView}。
 *
 * <p>命中的判定是**包含匹配**（不做正则、不做分词），所以运营能肉眼预测「这句话为什么被拦」——
 * 这是词表能不能被信任的前提。**停用不删**（{@code enabled=false}）：留着它才能解释
 * 「昨天为什么拦了那条内容」。
 *
 * <p>与 AI 侧的护栏词（{@code knowledge_guard_term}）不是一回事：那张表管模型**输出**里的
 * 药名与越界表述（ADR-0021），这张表管**用户发进来**的内容（CONTEXT.md 的硬红线条目：
 * 敏感词属内容审核域，与红线词无关）。
 *
 * @param id        词条 id
 * @param word      词条
 * @param category  分类标签（给运营自己看，不参与判定）
 * @param enabled   是否参与机审
 * @param remark    为什么拦这个词
 * @param updatedAt 最后修改时间
 */
public record SensitiveWordView(
        Long id,
        String word,
        String category,
        Boolean enabled,
        String remark,
        LocalDateTime updatedAt) {
}
