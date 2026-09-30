package com.pethealth.catalog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.catalog.domain.ServiceItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 目录项的数据访问。普通查询走 MyBatis-Plus（逻辑删除自动过滤）。
 */
@Mapper
public interface ServiceItemMapper extends BaseMapper<ServiceItem> {

    /**
     * 某个分类前缀下已用过的最大编码，用于排下一个序号（如 {@code HE-013} → 下一个 {@code HE-014}）。
     *
     * <p>**刻意不带 {@code is_deleted = 0}**：编码「一旦发布不可复用」（ADR-0034 的项目所有者口径），
     * 所以排号时要连历史上用过的码一起算进去。这也是唯一一处故意不按逻辑删除过滤的手写 SQL，
     * 改它之前先读 ADR-0034。
     *
     * @return 最大编码；该前缀下还没有任何项目时返回 {@code null}
     */
    @Select("""
            SELECT MAX(code)
              FROM service_item
             WHERE code LIKE CONCAT(#{prefix}, '-%')
            """)
    String maxCodeWithPrefix(@Param("prefix") String prefix);
}
