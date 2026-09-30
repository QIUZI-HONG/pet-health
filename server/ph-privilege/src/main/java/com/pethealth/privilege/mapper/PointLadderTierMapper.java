package com.pethealth.privilege.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.privilege.domain.PointLadderTier;
import org.apache.ibatis.annotations.Mapper;

/** 月度阶梯档位的数据访问（**种子里没有档位**——门槛与奖励不编，等运营配置）。 */
@Mapper
public interface PointLadderTierMapper extends BaseMapper<PointLadderTier> {
}
