package com.pethealth.privilege.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.privilege.domain.InviteRiskRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 反作弊拦截记录的数据访问。
 *
 * <p>与审核流水同一取舍：**append-only**。被拦下的邀请必须留得住——
 * 删一条记录就等于放了一次刷量过去，而事后没人能证明它发生过。
 */
@Mapper
public interface InviteRiskRecordMapper extends BaseMapper<InviteRiskRecord> {
}
