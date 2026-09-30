package com.pethealth.provider.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.provider.domain.AllianceCategory;
import com.pethealth.provider.domain.AllianceCategoryStat;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 联盟分类维度的数据访问。逻辑删除与审计字段由框架统一处理（ADR-0011）。
 *
 * <p>{@link #countByCategory()} 是唯一一条手写 SQL：归属数是一次分组聚合，
 * 逐档 {@code selectCount} 会变成 N+1（运营每开一次页面查一遍）。
 * 它读的是 `provider` 表——**本模块自己的表**，没有跨模块 join（ADR-0006）；
 * {@code is_deleted = 0} 由手写 SQL 自己带上（框架只作用于它生成的 SQL）。
 */
@Mapper
public interface AllianceCategoryMapper extends BaseMapper<AllianceCategory> {

    /** 每个维度当前归属的门店数（未软删的全部门店，不过滤经营状态）。 */
    @Select("""
            SELECT `category` AS `category_id`, COUNT(*) AS `provider_count`
              FROM `provider`
             WHERE `is_deleted` = 0
             GROUP BY `category`
            """)
    List<AllianceCategoryStat> countByCategory();
}
