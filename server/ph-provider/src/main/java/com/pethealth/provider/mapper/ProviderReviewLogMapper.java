package com.pethealth.provider.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.provider.domain.ProviderReviewLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 审核流水的数据访问。
 *
 * <p><b>只会有 insert 与 select</b>：这张表是 append-only 的（能改就等于没有）。
 * {@code BaseMapper} 在类型上仍提供 update/delete，本仓不写代码去调它们——
 * 与 ADR-0028 对审计表的约定一致。
 */
@Mapper
public interface ProviderReviewLogMapper extends BaseMapper<ProviderReviewLog> {
}
