package com.pethealth.account.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pethealth.account.domain.ComplianceDocument;
import com.pethealth.account.mapper.ComplianceDocumentMapper;
import com.pethealth.api.app.ComplianceDocumentView;
import com.pethealth.common.error.BusinessException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 合规文档（切片 #74）。**只读**：正文由运营/法务在库里维护（ADR-0025 的分工）。
 *
 * <p>不缓存：这些页面访问频率极低，而缓存一个「改了要立刻生效」的法律文本只会带来
 * 「用户看到旧条款」的风险。
 */
@Service
public class ComplianceService {

    private final ComplianceDocumentMapper documentMapper;

    public ComplianceService(ComplianceDocumentMapper documentMapper) {
        this.documentMapper = documentMapper;
    }

    public List<ComplianceDocumentView> list() {
        return documentMapper.selectList(Wrappers.<ComplianceDocument>lambdaQuery()
                        .orderByAsc(ComplianceDocument::getId))
                .stream().map(ComplianceService::toView).toList();
    }

    public ComplianceDocumentView get(String code) {
        ComplianceDocument document = documentMapper.selectOne(Wrappers.<ComplianceDocument>lambdaQuery()
                .eq(ComplianceDocument::getCode, code));
        if (document == null) {
            throw BusinessException.notFound("没有这份文档");
        }
        return toView(document);
    }

    private static ComplianceDocumentView toView(ComplianceDocument document) {
        return new ComplianceDocumentView(
                document.getCode(),
                document.getTitle(),
                document.getBody(),
                document.getVersion(),
                document.getEffectiveFrom(),
                document.getIsPlaceholder() != null && document.getIsPlaceholder() == 1);
    }
}
