package com.pethealth.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.account.domain.AuditLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 审计日志的数据访问。
 *
 * <p>只有 MyBatis-Plus 的通用方法：这个域**不提供查询接口**（审计的读取属于运营后台 #118 与
 * 排障场景，届时按需加），写入是唯一被允许的动作。
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
