package com.pethealth.reminder.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.reminder.domain.Message;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 消息的数据访问。逻辑删除由 MyBatis-Plus 自动过滤（ADR-0011）；
 * 下面两条手写 SQL 自己带 {@code is_deleted = 0}。
 */
@Mapper
public interface MessageMapper extends BaseMapper<Message> {

    /** 未读数。角标每次进页面都要算，所以是一条走索引的 count。 */
    @Select("""
            SELECT COUNT(1) FROM message
             WHERE user_id = #{userId} AND status = 1 AND is_deleted = 0
            """)
    int countUnread(@Param("userId") long userId);

    /** 其中健康提醒的未读数（首页与消息中心分开展示）。 */
    @Select("""
            SELECT COUNT(1) FROM message
             WHERE user_id = #{userId} AND status = 1 AND kind = 1 AND is_deleted = 0
            """)
    int countUnreadReminders(@Param("userId") long userId);
}
