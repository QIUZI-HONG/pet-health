package com.pethealth.privilege.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.privilege.domain.InviteCode;
import org.apache.ibatis.annotations.Mapper;

/**
 * 邀请码的数据访问。
 *
 * <p>「一人一码」由唯一键 {@code uk_user} 兜底：重复生成不会产生第二个码，
 * 服务层命中冲突时直接把已有的码返回（对用户来说「我的邀请码」永远是同一个）。
 */
@Mapper
public interface InviteCodeMapper extends BaseMapper<InviteCode> {
}
