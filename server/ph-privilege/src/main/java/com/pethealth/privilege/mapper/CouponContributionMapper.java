package com.pethealth.privilege.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.privilege.domain.CouponContribution;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 券贡献的数据访问。
 *
 * <p>{@link #selectForUpdate} 是**额度不超发的关键一环**：发放一张券之前，
 * 先在同一个事务里把这条贡献行锁住（{@code SELECT ... FOR UPDATE}），
 * 再算「还能不能发」。只靠先查后写（check-then-act）在并发下会超发——
 * 两个请求同时读到「还剩 1 张」，然后各发一张。
 *
 * <p>锁的粒度是**一条贡献**（服务者 × 模板），不是整张表：不同服务者的发券互不影响，
 * 这是本项目能不用分布式锁就做对这件事的原因。
 */
@Mapper
public interface CouponContributionMapper extends BaseMapper<CouponContribution> {

    /**
     * 取一条贡献并加行锁（必须在事务里调用）。
     *
     * @param id 贡献 id
     * @return 锁住的那一行；不存在返回 {@code null}
     */
    @Select("""
            SELECT *
              FROM coupon_contribution
             WHERE id = #{id}
               AND is_deleted = 0
             FOR UPDATE
            """)
    CouponContribution selectForUpdate(@Param("id") long id);
}
