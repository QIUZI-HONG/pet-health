package com.pethealth.record.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.record.domain.CheckInRewardRule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 打卡奖励档位（V41）。
 *
 * <p>表很小（本轮只有一行种子，运营最多配几档），所以整体读出来在内存里挑——
 * 与 {@code CareModeRuleMapper} 同一种取舍，不需要缓存，也不用担心每次打卡都查一次库。
 */
@Mapper
public interface CheckInRewardRuleMapper extends BaseMapper<CheckInRewardRule> {

    /**
     * 启用的档位，按连续天数升序。
     *
     * <p>手写 SQL 必须自己带 {@code is_deleted = 0}（ADR-0011）——框架的逻辑删除只作用于
     * 它自己生成的语句。`status` 条件写在这里而不是让调用方过滤：**停用的档位不参与判定**
     * 是这张表的语义（与「停用的任务不下发」同一条口径），漏掉它就是多发券。
     */
    @Select("""
            SELECT * FROM check_in_reward_rule
             WHERE status = 1 AND is_deleted = 0
             ORDER BY streak_days
            """)
    List<CheckInRewardRule> selectEnabled();
}
