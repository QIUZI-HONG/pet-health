package com.pethealth.record.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.record.domain.HealthScore;
import org.apache.ibatis.annotations.Mapper;

/**
 * 健康评分的数据访问。逻辑删除由 MyBatis-Plus 自动过滤。
 *
 * <p>当日行的存在性判断与更新都用 QueryWrapper 完成（见 {@code HealthScoreService}）——
 * 唯一键 {@code uk_pet_calc_date} 保证一天只有一行。
 */
@Mapper
public interface HealthScoreMapper extends BaseMapper<HealthScore> {
}
