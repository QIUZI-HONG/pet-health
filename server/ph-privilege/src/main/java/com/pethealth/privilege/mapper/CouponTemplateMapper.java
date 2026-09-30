package com.pethealth.privilege.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.privilege.domain.CouponTemplate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 券模板的数据访问。日常读写走 MyBatis-Plus（逻辑删除与审计字段由框架统一处理）。
 *
 * <p>两个手写 SQL 都是**聚合**：MyBatis-Plus 的逻辑删除只作用于它自己生成的条件，
 * 手写 SQL 必须自己带 {@code is_deleted = 0}（ADR-0011 的提醒）。
 */
@Mapper
public interface CouponTemplateMapper extends BaseMapper<CouponTemplate> {

    /**
     * 取券模板并加行锁（必须在事务里调用）。
     *
     * <p>发放的第一条 SQL 就是它，两个作用：
     *
     * <ol>
     *   <li>**当前读**：它不建立一致性读的快照，所以后面数「已发放多少张」时看到的是
     *       最新已提交的数据（详见 {@code CouponService} 的隔离级别说明）；
     *   <li>**同一模板的发放串行**：平台补贴券的发放上限（{@code issue_limit}）没有别的锁可依靠，
     *       锁模板行是它唯一的闸。
     * </ol>
     */
    @Select("""
            SELECT *
              FROM coupon_template
             WHERE id = #{id}
               AND is_deleted = 0
             FOR UPDATE
            """)
    CouponTemplate selectForUpdate(@Param("id") long id);

    /**
     * 一批模板各自已发放多少张（运营列表要显示「上限还剩多少」）。
     *
     * <p>按 template_id 一次取回，不是每行查一次——列表最多 100 行，
     * 一次分组查询比 100 次 count 便宜得多。
     *
     * @return 每行 {@code {template_id, issued}}
     */
    @Select("""
            <script>
            SELECT template_id AS template_id, COUNT(*) AS issued
              FROM coupon
             WHERE is_deleted = 0
               AND template_id IN
               <foreach collection="templateIds" item="id" open="(" separator="," close=")">#{id}</foreach>
             GROUP BY template_id
            </script>
            """)
    List<Map<String, Object>> countIssuedByTemplates(@Param("templateIds") List<Long> templateIds);
}
