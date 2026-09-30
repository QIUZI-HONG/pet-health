package com.pethealth.privilege.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.privilege.domain.PointExchangeOption;
import org.apache.ibatis.annotations.Mapper;

/**
 * 兑换档位的数据访问。
 *
 * <p>「只能兑平台补贴券」的校验在服务层（要读券模板的 {@code cost_bearer}），
 * 不在这里——那是业务规则，不是数据访问。
 */
@Mapper
public interface PointExchangeOptionMapper extends BaseMapper<PointExchangeOption> {
}
