package com.pethealth.catalog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.catalog.domain.ServiceCategory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 目录分类的数据访问。普通查询走 MyBatis-Plus（逻辑删除自动过滤）。
 */
@Mapper
public interface ServiceCategoryMapper extends BaseMapper<ServiceCategory> {

    /**
     * 分类下的**启用**项目数（一次 group by 取回全部，避免每个分类查一次）。
     *
     * <p>手写 SQL 必须自己带 {@code is_deleted = 0}（逻辑删除插件不拦手写 SQL，ADR-0011）。
     * 只数启用项：停用项如果也计入，运营会看到「分类下有 20 项」却只列出 18 项。
     *
     * @return 每行两个键：{@code category_code} 与 {@code item_count}
     */
    @Select("""
            SELECT category_code, COUNT(*) AS item_count
              FROM service_item
             WHERE status = 1
               AND is_deleted = 0
             GROUP BY category_code
            """)
    java.util.List<java.util.Map<String, Object>> countEnabledItemsByCategory();

    /** 分类下是否还有项目（含停用、含已软删的）——用于拦住「删掉还有引用的分类」。 */
    @Select("""
            SELECT COUNT(*)
              FROM service_item
             WHERE category_code = #{categoryCode}
            """)
    long countItemsIncludingDeleted(@Param("categoryCode") String categoryCode);
}
