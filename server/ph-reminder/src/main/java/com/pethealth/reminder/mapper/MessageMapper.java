package com.pethealth.reminder.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.reminder.domain.Message;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

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

    /**
     * 按幂等键找消息，**包含已软删的**。
     *
     * <p>为什么要绕过逻辑删除：{@code uk_dedup} 是物理唯一键，软删的行**仍然占着那个键**。
     * 用户删掉一条提醒后，同一条件再满足时（例如「今天还没记录」今天还在），
     * 生成器必须能看见这条已删的行——否则会去 insert，撞唯一键报 500（切片 #99 的缺陷）。
     */
    @Select("SELECT * FROM message WHERE dedup_key = #{dedupKey} LIMIT 1")
    Message findByDedupKeyIncludingDeleted(@Param("dedupKey") String dedupKey);

    /**
     * 插入一条消息，**同键已存在就什么都不做**（返回 0）。
     *
     * <p>为什么不是「先查后插」再 catch 唯一键冲突：查与插之间有一个窗口，首页同时拉列表与强提醒流
     * （或批算与用户请求并发）时两边都查不到、都插入，后插入的撞 {@code uk_dedup}。
     * 而**在事务里撞唯一键会把事务标记成 rollback-only**，catch 住异常也救不回来——提交时照样 500
     * （同一提交里打卡那条路径就是这么实测出来的）。所以这里用「冲突即 no-op」的一条 SQL，
     * 从根上不产生异常。
     *
     * <p>no-op 的语义与「删除不等于关闭该类型」一致：被用户删掉的那条仍占着键，这里不会复活它。
     */
    @Insert("""
            INSERT INTO message
                (user_id, pet_id, kind, type, title, content, risk_level, remind_at, status, read_at,
                 dedup_key, action_hint, action_target, channel_state,
                 created_at, updated_at, created_by, updated_by, trace_id, is_deleted)
            VALUES
                (#{userId}, #{petId}, #{kind}, #{type}, #{title}, #{content}, #{riskLevel}, #{remindAt},
                 #{status}, NULL, #{dedupKey}, #{actionHint}, #{actionTarget}, #{channelState},
                 #{now}, #{now}, #{operatorId}, #{operatorId}, #{traceId}, 0)
            ON DUPLICATE KEY UPDATE dedup_key = dedup_key
            """)
    int insertIfAbsent(@Param("userId") long userId,
                       @Param("petId") Long petId,
                       @Param("kind") int kind,
                       @Param("type") int type,
                       @Param("title") String title,
                       @Param("content") String content,
                       @Param("riskLevel") int riskLevel,
                       @Param("remindAt") LocalDateTime remindAt,
                       @Param("status") int status,
                       @Param("dedupKey") String dedupKey,
                       @Param("actionHint") String actionHint,
                       @Param("actionTarget") String actionTarget,
                       @Param("channelState") String channelState,
                       @Param("now") LocalDateTime now,
                       @Param("operatorId") long operatorId,
                       @Param("traceId") String traceId);

    /** 其中健康提醒的未读数（首页与消息中心分开展示）。 */
    @Select("""
            SELECT COUNT(1) FROM message
             WHERE user_id = #{userId} AND status = 1 AND kind = 1 AND is_deleted = 0
            """)
    int countUnreadReminders(@Param("userId") long userId);
}
