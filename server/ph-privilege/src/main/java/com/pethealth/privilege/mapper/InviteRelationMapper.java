package com.pethealth.privilege.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.privilege.domain.InviteRelation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 邀请关系的数据访问。
 *
 * <p>两个手写 SQL 各服务一件事：找**待结算**的关系（结算批算的输入），
 * 以及数**有效邀请数**（阶梯计数的唯一依据——按有效数，不按注册数）。
 */
@Mapper
public interface InviteRelationMapper extends BaseMapper<InviteRelation> {

    /**
     * 该用户当前的有效邀请数——阶梯达成判定的输入。
     *
     * <p>**只算「有效」**（ADR-0039：有效邀请 = 被邀请人完成建档 + 24 小时内有行为）：
     * 按注册数算的话，刷出来的号当天就能把阶梯奖领走。
     */
    @Select("""
            SELECT COUNT(*)
              FROM invite_relation
             WHERE is_deleted = 0
               AND inviter_user_id = #{inviterUserId}
               AND status = 2
            """)
    int countEffectiveOf(@Param("inviterUserId") long inviterUserId);

    /**
     * 到结算时刻仍「待生效」的关系（结算批算的输入）。
     *
     * <p>结算条件：建档已完成，且归因时刻已经过了观察窗（{@code observeHours} 小时）——
     * 「24 小时内无行为不发券」这条规则只有在观察窗结束后才能下有结论。
     */
    @Select("""
            SELECT *
              FROM invite_relation
             WHERE is_deleted = 0
               AND status = 1
               AND profile_completed_at IS NOT NULL
               AND attributed_at <= #{deadline}
             ORDER BY id
             LIMIT #{limit}
            """)
    List<InviteRelation> findSettleable(@Param("deadline") LocalDateTime deadline,
                                       @Param("limit") int limit);

    /** 某人的邀请关系里各状态的条数（运营总览用）。 */
    @Select("""
            SELECT COUNT(*)
              FROM invite_relation
             WHERE is_deleted = 0
               AND status = #{status}
            """)
    int countByStatus(@Param("status") int status);
}
