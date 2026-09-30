package com.pethealth.provider.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.provider.domain.Provider;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 服务者表的数据访问。逻辑删除与审计字段由框架统一处理（ADR-0011），这里不需要手写 SQL。
 *
 * <p>例外是 {@link #updateRating}：**评分回写必须手写一条只改一列的 UPDATE**，
 * 不能拿一个「只 set 了 rating 的实体」去 {@code updateById}。理由是
 * {@link Provider} 里那几个 {@code @TableField(updateStrategy = ALWAYS)} 的字段
 * （{@code intro} / {@code businessHours} / {@code lng} / {@code lat}）：ALWAYS 的含义是
 * 「即使是 null 也要进 SET」——为的是让「清空简介」「清掉坐标」这类动作能落库。
 * 反过来，一个**局部实体**拿去 updateById 时，那些字段的 null 会把门店的营业时间、简介与坐标
 * 一起抹掉（实测：评价回写之后该门店的 `business_hours` 变 NULL，下一次下单直接
 * 「该门店当天不营业」——评价切片的集成测试逮到的就是这个）。
 */
@Mapper
public interface ProviderMapper extends BaseMapper<Provider> {

    /**
     * 只改评分（评价聚合回写的唯一入口）。
     *
     * <p>手写 SQL 不走 {@code AuditMetaObjectHandler}（它只作用于框架生成的 SQL），
     * 所以 {@code updated_at / updated_by / trace_id} 必须显式写——「所有写操作留 operator_id
     * 与 trace_id」是硬约束。调用方传的是**当前链路**的操作者与 trace（谁触发了这次回写）。
     *
     * @return 受影响行数；0 表示门店不存在（或已被软删），调用方按「跳过」处理
     */
    @Update("""
            UPDATE `provider`
               SET `rating` = #{rating},
                   `updated_at` = #{now},
                   `updated_by` = #{operatorId},
                   `trace_id` = #{traceId}
             WHERE `id` = #{providerId}
               AND `is_deleted` = 0
            """)
    int updateRating(@Param("providerId") long providerId, @Param("rating") BigDecimal rating,
                     @Param("operatorId") long operatorId, @Param("traceId") String traceId,
                     @Param("now") LocalDateTime now);
}
