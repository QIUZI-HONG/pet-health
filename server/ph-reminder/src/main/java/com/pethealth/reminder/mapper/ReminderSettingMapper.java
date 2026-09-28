package com.pethealth.reminder.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.reminder.domain.ReminderSetting;
import org.apache.ibatis.annotations.Mapper;

/** 用户提醒开关的数据访问。 */
@Mapper
public interface ReminderSettingMapper extends BaseMapper<ReminderSetting> {
}
