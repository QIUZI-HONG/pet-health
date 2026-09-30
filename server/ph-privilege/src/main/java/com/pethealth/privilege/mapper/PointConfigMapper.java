package com.pethealth.privilege.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.privilege.domain.PointConfig;
import org.apache.ibatis.annotations.Mapper;

/** 积分规则设置（单行表）的数据访问。 */
@Mapper
public interface PointConfigMapper extends BaseMapper<PointConfig> {
}
