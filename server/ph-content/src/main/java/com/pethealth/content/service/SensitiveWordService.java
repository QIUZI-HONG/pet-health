package com.pethealth.content.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pethealth.api.content.SensitiveWordRequest;
import com.pethealth.api.content.SensitiveWordView;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.util.Text;
import com.pethealth.content.domain.SensitiveWord;
import com.pethealth.content.mapper.SensitiveWordMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

/**
 * 机审词表：维护（运营）+ 扫描（所有发文路径）。
 *
 * <p><b>为什么词表在库里</b>（ADR-0010 的业务可调项分层）：词表是随热点变的运营资产。
 * 写死在代码里意味着改一次要发一次版，实际效果是没人会改——而内容审核的时效性正是它的全部价值。
 *
 * <p><b>判定只有一条规则</b>：把文本与每个启用词都做**小写化的包含匹配**。不做正则、不做分词——
 * 运营要能肉眼预测「这句话为什么被拦」，这是词表能不能被信任的前提；正则的威力换来的是
 * 「谁也说不清哪条会被拦」，那正是误伤的来源。词量在几十到几百条时，逐词 contains 的成本可以忽略。
 *
 * <p><b>不缓存</b>：与权益判定同一取舍（ADR-0045 第一节）——词表决定内容能不能发出去，
 * 缓存漂移的代价是「运营刚停掉的词还在拦人」，而这里查的是一张几十行、带索引的小表。
 *
 * <p>扫描结果**不是布尔值**而是命中的词列表：运营改判时要知道当时命中了什么
 * （{@code machine_hits} 落库），作者申诉时也才解释得清。
 */
@Service
public class SensitiveWordService {

    private final SensitiveWordMapper wordMapper;

    public SensitiveWordService(SensitiveWordMapper wordMapper) {
        this.wordMapper = wordMapper;
    }

    // ---------------------------------------------------------------- 机审

    /**
     * 扫描若干段文本，返回命中的词（逗号分隔）；**没命中返回 null**。
     *
     * <p>为什么返回 null 而不是空串：库里的 {@code machine_hits} 用 NULL 表示「没命中」，
     * 空串会是「命中了但一个词都没有」这种读不出来的状态（docs/conventions.md 的空值口径）。
     */
    @Transactional(readOnly = true)
    public String hits(String... texts) {
        StringJoiner hit = new StringJoiner(",");
        List<String> lowered = new ArrayList<>();
        for (String text : texts) {
            if (text != null && !text.isBlank()) {
                lowered.add(text.toLowerCase(Locale.ROOT));
            }
        }
        if (lowered.isEmpty()) {
            return null;
        }
        for (SensitiveWord word : enabledWords()) {
            String needle = word.getWord().toLowerCase(Locale.ROOT);
            for (String text : lowered) {
                if (text.contains(needle)) {
                    hit.add(word.getWord());
                    break;
                }
            }
        }
        return hit.length() == 0 ? null : hit.toString();
    }

    private List<SensitiveWord> enabledWords() {
        return wordMapper.selectList(Wrappers.<SensitiveWord>lambdaQuery()
                .eq(SensitiveWord::getEnabled, 1)
                .orderByAsc(SensitiveWord::getId));
    }

    // ---------------------------------------------------------------- 运营维护

    /** 词表分页（含停用；运营要能看见自己停用过的词）。 */
    @Transactional(readOnly = true)
    public PageResult<SensitiveWordView> list(Boolean enabled, String keyword, long page, long pageSize) {
        Page<SensitiveWord> result = wordMapper.selectPage(Page.of(page, pageSize),
                Wrappers.<SensitiveWord>lambdaQuery()
                        .eq(enabled != null, SensitiveWord::getEnabled, Boolean.TRUE.equals(enabled) ? 1 : 0)
                        .like(Text.trimToNull(keyword) != null, SensitiveWord::getWord, Text.trimToNull(keyword))
                        .orderByDesc(SensitiveWord::getId));
        return PageResult.from(result, SensitiveWordService::view);
    }

    /** 新增一条词；同一句词重复添加 → 40900（唯一键也是这个口径）。 */
    @Transactional
    public SensitiveWordView create(SensitiveWordRequest request) {
        String word = Text.trimToNull(request.word());
        SensitiveWord entity = new SensitiveWord();
        entity.setWord(word);
        entity.setCategory(Text.trimToNull(request.category()));
        entity.setEnabled(request.enabled() == null || request.enabled() ? 1 : 0);
        entity.setRemark(Text.trimToNull(request.remark()));
        try {
            wordMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            throw BusinessException.conflict("这个词已经在词表里了");
        }
        return view(entity);
    }

    /**
     * 修改一条词（改词 / 启停 / 说明）。
     *
     * <p>没有删除动作：停用就是「不再拦」，而留着它才能解释「昨天为什么拦了那条内容」
     * （契约里写明了这个口径）。
     */
    @Transactional
    public SensitiveWordView update(long wordId, SensitiveWordRequest request) {
        SensitiveWord entity = wordMapper.selectById(wordId);
        if (entity == null) {
            throw BusinessException.notFound("词条不存在");
        }
        entity.setWord(Text.trimToNull(request.word()));
        entity.setCategory(Text.trimToNull(request.category()));
        // 不传 enabled 表示「不动开关」：改个说明顺手把词启用/停用，是最容易踩的一脚
        if (request.enabled() != null) {
            entity.setEnabled(request.enabled() ? 1 : 0);
        }
        entity.setRemark(Text.trimToNull(request.remark()));
        try {
            wordMapper.updateById(entity);
        } catch (DuplicateKeyException e) {
            throw BusinessException.conflict("改后的词与词表里已有的词重复");
        }
        return view(entity);
    }

    static SensitiveWordView view(SensitiveWord word) {
        return new SensitiveWordView(
                word.getId(),
                word.getWord(),
                word.getCategory(),
                word.getEnabled() != null && word.getEnabled() == 1,
                word.getRemark(),
                word.getUpdatedAt());
    }
}
