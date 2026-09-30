package com.pethealth.privilege.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.privilege.domain.PointBehavior;
import org.apache.ibatis.annotations.Mapper;

/**
 * 积分行为分值表的数据访问。
 *
 * <p>没有 insert 路径：行为码是代码常量，**新增行为是代码变更**（ADR-0046），
 * 运营只能改分值、频次与启停。
 */
@Mapper
public interface PointBehaviorMapper extends BaseMapper<PointBehavior> {
}
