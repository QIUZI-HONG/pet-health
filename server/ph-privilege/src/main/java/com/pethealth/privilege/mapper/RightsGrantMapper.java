package com.pethealth.privilege.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.privilege.domain.RightsGrant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 权益授予记录的数据访问。
 *
 * <p>{@link #revokeExpired} 是**到期回收**（ADR-0038 第三节：「到期只回收该来源授予的那一条」）：
 * 一条条件更新把所有「已过期且仍生效」的记录翻成已回收。逐个 id 更新会在批算里产生 N 条 SQL，
 * 而这里只需要「过期」这一个判据——订阅到期不会碰到邀请得的永久权益（那条没有到期时间）。
 */
@Mapper
public interface RightsGrantMapper extends BaseMapper<RightsGrant> {

    /**
     * 回收到期记录，返回回收条数。
     *
     * <p>条件更新所以**幂等**：重复跑第二次影响 0 行（第一次已经把状态翻了）。
     * 只动 {@code status = 1} 的行——已回收的不再动，回收时刻也不会被覆盖。
     */
    @Update("""
            UPDATE rights_grant
               SET status = 2,
                   revoked_at = #{now},
                   remark = '到期自动回收',
                   updated_at = #{now}
             WHERE is_deleted = 0
               AND status = 1
               AND expire_at IS NOT NULL
               AND expire_at < #{now}
            """)
    int revokeExpired(@Param("now") LocalDateTime now);
}
