package com.pethealth.privilege.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.privilege.domain.CouponContributionLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 额度流水的数据访问。
 *
 * <p><b>只会有 insert 与 select</b>：这张表是 append-only 的（能改就等于没有），
 * 与 {@code provider_review_log} 同一取舍。
 */
@Mapper
public interface CouponContributionLogMapper extends BaseMapper<CouponContributionLog> {
}
