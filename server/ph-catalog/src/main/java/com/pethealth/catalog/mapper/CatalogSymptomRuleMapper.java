package com.pethealth.catalog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.catalog.domain.CatalogSymptomRule;
import org.apache.ibatis.annotations.Mapper;

/**
 * 症状映射的数据访问。日常读写都走 MyBatis-Plus（逻辑删除与审计字段由框架统一处理）；
 * F011 只读启用中的映射（{@code enabled = 1}），停用的行留着是为了「这条映射以前有用过」可追溯。
 */
@Mapper
public interface CatalogSymptomRuleMapper extends BaseMapper<CatalogSymptomRule> {
}
