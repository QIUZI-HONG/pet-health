package com.pethealth.privilege.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.privilege.domain.RightsCode;
import org.apache.ibatis.annotations.Mapper;

/**
 * 权益码表的数据访问。
 *
 * <p>码表只有运营的增删改查与各模块的只读判定，没有聚合需求，所以只继承 {@code BaseMapper}。
 */
@Mapper
public interface RightsCodeMapper extends BaseMapper<RightsCode> {
}
