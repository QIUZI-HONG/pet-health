package com.pethealth.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.account.domain.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户表的数据访问。逻辑删除由 MyBatis-Plus 按 {@code is_deleted = 0} 自动过滤（ADR-0011）。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
