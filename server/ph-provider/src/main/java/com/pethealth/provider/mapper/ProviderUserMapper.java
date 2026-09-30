package com.pethealth.provider.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.provider.domain.ProviderUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * 服务者账号绑定的数据访问。
 *
 * <p>唯一键 {@code uk_provider_user(provider_id, user_id)} 是「同一个人在同一家只能有一条记录」的
 * 兜底：技师是管理员的权限子集，兼任时共用同一条 {@code role=1} 记录（不建两套账号体系）。
 */
@Mapper
public interface ProviderUserMapper extends BaseMapper<ProviderUser> {
}
