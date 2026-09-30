package com.pethealth.privilege.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.privilege.domain.ProviderInviteCode;
import org.apache.ibatis.annotations.Mapper;

/**
 * 门店推广码的数据访问。逻辑删除与审计字段由框架统一处理（ADR-0011），没有手写 SQL：
 * 查找口径一共两条（按门店、按码），都用得到通用方法，不需要为它们各写一段 SQL。
 */
@Mapper
public interface ProviderInviteCodeMapper extends BaseMapper<ProviderInviteCode> {
}
