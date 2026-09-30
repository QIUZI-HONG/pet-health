package com.pethealth.privilege.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.privilege.domain.PointLadderGrant;
import org.apache.ibatis.annotations.Mapper;

/**
 * 月度阶梯发放记录的数据访问。
 *
 * <p>{@code (user_id, period)} 唯一：批算重跑与多实例并发都命在这条唯一键上。
 * 服务层在插入前会先查一次（给出可读的结果），但**真正兜底的是唯一键**——
 * 「先查再插」在并发下会漏。
 */
@Mapper
public interface PointLadderGrantMapper extends BaseMapper<PointLadderGrant> {
}
