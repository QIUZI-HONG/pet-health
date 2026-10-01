package com.pethealth.privilege.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.privilege.domain.InviteeRewardRule;
import org.apache.ibatis.annotations.Mapper;

/**
 * 被邀请人奖励规则（单行表 {@code invitee_reward_rule}，V46）。读法只有一处：
 * {@code InviteService#grantInviteeCoupon}。
 */
@Mapper
public interface InviteeRewardRuleMapper extends BaseMapper<InviteeRewardRule> {
}
