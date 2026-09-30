package com.pethealth.ai.service;

import com.pethealth.ai.client.AiServiceClient;
import com.pethealth.ai.domain.RiskLevel;
import com.pethealth.api.app.KnowledgeEntryView;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.error.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 知识库浏览（F024 / F025）：把知识层**只读地**开给 C 端。
 *
 * <p>为什么它落在 ph-ai 而不是别处：知识条目只在 AI 服务那边能读（ADR-0009 的例外就是它），
 * 这一层只做「透传 + 字段收口」——把 AI 侧的 `risk_hint`（green/yellow/red）翻成 C 端
 * 统一的分级口径（1/2/3），并**只挑 C 端该看的字段**下发（信心度、内部分类提示这些不给）。
 *
 * <p>两条边界：**读不到知识库不是错误**（`knowledgeCheck=unavailable` → 空列表 + 由调用方按
 * 「暂时看不了」处理，ADR-0026 的降级口径）；**不可读的编号一律 40400**（含不存在 / 已删除 /
 * 复核状态不可读——与「看不到」同码，免得用编号探测哪些条目存在过）。
 */
@Service
public class KnowledgeBrowseService {

    private final AiServiceClient aiClient;

    public KnowledgeBrowseService(AiServiceClient aiClient) {
        this.aiClient = aiClient;
    }

    @Transactional(readOnly = true)
    public PageResult<KnowledgeEntryView> page(String categoryCode, String keyword, long page, long pageSize) {
        AiServiceClient.KnowledgeBrowseResponse response =
                aiClient.browseKnowledge(categoryCode, keyword, null, page, pageSize);
        List<KnowledgeEntryView> list = response.items().stream()
                .map(KnowledgeBrowseService::toView)
                .toList();
        // total 由 AI 侧给（它做了 COUNT）：前端分页器要的是「符合条件的总数」，不是「这一页几条」
        return PageResult.of(list, page, pageSize, response.total());
    }

    @Transactional(readOnly = true)
    public KnowledgeEntryView detail(String code) {
        AiServiceClient.KnowledgeBrowseResponse response = aiClient.browseKnowledge(null, null, code, 1, 1);
        return response.items().stream().findFirst()
                .map(KnowledgeBrowseService::toView)
                // 空就是「读不到这一条」：不存在、已删、或不可读三种情况对外是同一件事
                .orElseThrow(() -> BusinessException.notFound("知识条目不存在"));
    }

    private static KnowledgeEntryView toView(AiServiceClient.KnowledgeBrowseItem item) {
        return new KnowledgeEntryView(
                item.code(),
                item.title(),
                item.summary(),
                item.body() == null ? "" : item.body(),
                item.categoryCode(),
                item.categoryName(),
                RiskLevel.codeOfHint(item.riskHint()),
                item.reviewStatus(),
                item.sourceTitle(),
                item.sourceUrl());
    }
}
