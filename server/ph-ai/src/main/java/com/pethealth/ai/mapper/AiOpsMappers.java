package com.pethealth.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.ai.domain.AiOpsTables;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 运营可调项四张配置表 + 红线词表的读写。
 *
 * <p>五个 {@code BaseMapper} 收在一个文件里（同 {@code domain/AiOpsTables} 的理由：它们是
 * 纯 CRUD，没有任何自定义 SQL——真正的判断在 {@code AiOpsService}）。
 * 自定义查询（如按 prompt_version 抽检）写在各自的实体注释指到的地方。
 */
public final class AiOpsMappers {

    private AiOpsMappers() {
    }

    @Mapper
    public interface PromptTemplateMapper extends BaseMapper<AiOpsTables.PromptTemplate> {
    }

    @Mapper
    public interface GradingRuleMapper extends BaseMapper<AiOpsTables.GradingRule> {
    }

    @Mapper
    public interface GuardTermMapper extends BaseMapper<AiOpsTables.GuardTerm> {
    }

    @Mapper
    public interface SwitchMapper extends BaseMapper<AiOpsTables.Switch> {
    }

    @Mapper
    public interface RedFlagMapper extends BaseMapper<AiOpsTables.RedFlag> {
    }
}
