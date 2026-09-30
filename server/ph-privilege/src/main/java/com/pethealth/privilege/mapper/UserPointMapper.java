package com.pethealth.privilege.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.privilege.domain.UserPoint;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 积分账户的数据访问。
 *
 * <p>两条手写 SQL 守住「余额不会错」这件事：
 *
 * <ul>
 *   <li>{@link #selectForUpdate}：发分前锁住账户行，保证「读余额 → 算变动后余额 → 写流水」
 *       这一串在同一个事务里是串行的。同一用户并发发分不会丢更新；
 *   <li>{@link #spend}：兑换扣分用**原子的条件更新**——
 *       {@code WHERE balance >= amount} 写在 SQL 里，扣不成就影响 0 行。
 *       先查余额再扣（check-then-act）在并发下会让余额变成负数。
 * </ul>
 */
@Mapper
public interface UserPointMapper extends BaseMapper<UserPoint> {

    /** 取账户并加行锁（必须在事务里调用）；账户不存在返回 {@code null}（首次发分时才建）。 */
    @Select("""
            SELECT *
              FROM user_point
             WHERE user_id = #{userId}
               AND is_deleted = 0
             FOR UPDATE
            """)
    UserPoint selectForUpdate(@Param("userId") long userId);

    /**
     * 原子扣分：余额足够才扣。
     *
     * @return 影响行数；0 表示余额不足（或账户不存在），调用方据此回「积分不足」
     */
    @Update("""
            UPDATE user_point
               SET balance = balance - #{amount},
                   total_spent = total_spent + #{amount},
                   updated_at = #{now}
             WHERE user_id = #{userId}
               AND is_deleted = 0
               AND balance >= #{amount}
            """)
    int spend(@Param("userId") long userId, @Param("amount") int amount,
              @Param("now") java.time.LocalDateTime now);
}
