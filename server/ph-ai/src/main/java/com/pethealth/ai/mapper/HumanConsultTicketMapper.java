package com.pethealth.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.ai.domain.HumanConsultTicket;
import org.apache.ibatis.annotations.Mapper;

/**
 * 转人工工单的数据访问。日常读写都走 MyBatis-Plus（逻辑删除与审计字段由框架统一处理）。
 *
 * <p>幂等靠 `consult_id` 上的唯一键：并发两次提交里只会有一条落库，另一条撞键——
 * 服务层捕获后读回已存在的那一条（**不做「先查后插」**，那在并发下会双双通过，见 ADR-0044）。
 */
@Mapper
public interface HumanConsultTicketMapper extends BaseMapper<HumanConsultTicket> {
}
