package com.pethealth.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.order.domain.OrderReview;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

/**
 * 评价表的数据访问。
 *
 * <p>两个自定义查询都是**聚合口径**（在这里写死一处，别处不再拼）：
 *
 * <ul>
 *   <li>{@link #averageRating} 算门店平均分——用 SQL 的 AVG 而不是把行捞回来在内存里平均：
 *       一家旺店可以有上万条评价，而回写的只是一个数。**软删除条件必须自己写**
 *       （{@code is_deleted = 0}）：MyBatis-Plus 的逻辑删除只自动加在 BaseMapper 的方法上，
 *       手写 SQL 不带它就会把删掉的评价也算进平均分；
 *   <li>{@link #countByOrder} 数某单的评价条数——「一单一评」的唯一键是写侧的兜底，
 *       这个是读侧的判据（用来给调用方一个明确的 40900，而不是一个 50000）。
 * </ul>
 */
@Mapper
public interface OrderReviewMapper extends BaseMapper<OrderReview> {

    /**
     * 某门店所有评价的平均分（一位小数由调用方定标）。
     *
     * @return 平均分；**一条评价都没有时返回 {@code null}**（不是 0——0 分是「很差」，
     *         与「还没有人评」是两件事，调用方要能分开）
     */
    @Select("""
            SELECT AVG(rating)
              FROM order_review
             WHERE is_deleted = 0
               AND provider_id = #{providerId}
            """)
    BigDecimal averageRating(@Param("providerId") long providerId);

    /** 某订单已提交的评价条数（一单一评的读侧判据）。 */
    @Select("""
            SELECT COUNT(*)
              FROM order_review
             WHERE is_deleted = 0
               AND order_id = #{orderId}
            """)
    int countByOrder(@Param("orderId") long orderId);
}
