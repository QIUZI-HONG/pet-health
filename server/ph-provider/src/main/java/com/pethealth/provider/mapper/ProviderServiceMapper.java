package com.pethealth.provider.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.provider.domain.ProviderServiceListing;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 服务者服务项的数据访问。日常读写都走 MyBatis-Plus（逻辑删除与审计字段由框架统一处理）。
 */
@Mapper
public interface ProviderServiceMapper extends BaseMapper<ProviderServiceListing> {

    /** 某个目录项编码被多少条服务项引用（含已下架、已驳回）——目录侧要知道「还有人在用吗」。 */
    @Select("""
            SELECT COUNT(*)
              FROM provider_service
             WHERE service_code = #{serviceCode}
               AND is_deleted = 0
            """)
    long countByServiceCode(@Param("serviceCode") String serviceCode);
}
