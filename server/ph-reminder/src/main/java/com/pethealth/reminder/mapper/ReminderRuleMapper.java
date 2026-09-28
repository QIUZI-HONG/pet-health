package com.pethealth.reminder.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.reminder.domain.ReminderRule;
import org.apache.ibatis.annotations.Mapper;

/** 提醒规则与阈值的数据访问（运营可改，本期只落表与默认值）。 */
@Mapper
public interface ReminderRuleMapper extends BaseMapper<ReminderRule> {
}
