package com.pethealth.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.content.domain.SensitiveWord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 机审敏感词的数据访问。
 *
 * <p>只用到框架的 CRUD：词表的量级是几十到几百条，查询走 {@code enabled + is_deleted} 索引，
 * 没有需要手写 SQL 的地方（也就没有 {@code AuditMetaObjectHandler} 覆盖不到的问题——
 * 那张表上的写操作全走框架生成的 SQL，审计列由框架填）。
 */
@Mapper
public interface SensitiveWordMapper extends BaseMapper<SensitiveWord> {
}
