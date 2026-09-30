package com.pethealth.provider.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.provider.domain.AssessmentLevelRule;
import org.apache.ibatis.annotations.Mapper;

/**
 * AssessmentLevelRule 的数据访问。逻辑删除与审计字段由框架统一处理（ADR-0011），这里不需要手写 SQL。
 *
 * <p>本切片**没有跨表聚合**：考核的三个数分别来自 {@code ph-order} 与 {@code ph-privilege}
 * 的只读接口（ADR-0006，不 join 别人的表），自己的四张表只按 score_id / provider_id 取行。
 */
@Mapper
public interface AssessmentLevelRuleMapper extends BaseMapper<AssessmentLevelRule> {
}
