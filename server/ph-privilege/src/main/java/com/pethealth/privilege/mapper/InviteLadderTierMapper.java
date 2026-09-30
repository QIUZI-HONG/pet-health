package com.pethealth.privilege.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.privilege.domain.InviteLadderTier;
import org.apache.ibatis.annotations.Mapper;

/** 邀请阶梯档位的数据访问（门槛固定五档，运营只改奖励物）。 */
@Mapper
public interface InviteLadderTierMapper extends BaseMapper<InviteLadderTier> {
}
