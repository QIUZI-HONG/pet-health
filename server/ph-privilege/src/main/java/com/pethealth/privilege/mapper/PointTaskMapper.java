package com.pethealth.privilege.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.privilege.domain.PointTask;
import org.apache.ibatis.annotations.Mapper;

/** 积分任务清单的数据访问（清单入库存配置：新增 / 改动任务不需要发版）。 */
@Mapper
public interface PointTaskMapper extends BaseMapper<PointTask> {
}
