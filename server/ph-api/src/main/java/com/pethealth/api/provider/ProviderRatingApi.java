package com.pethealth.api.provider;

import java.math.BigDecimal;

/**
 * 门店评分（{@code provider.rating}）的**回写端口**：谁拥有那张表，谁提供实现（ADR-0006）。
 *
 * <p>为什么需要一个端口：评分是**评价聚合出来的派生值**，而评价行在订单域
 * （{@code order_review} 是 ph-order 的表）；{@code provider.rating} 又在服务者域。
 * 于是「算」和「写」必然分在两侧：
 *
 * <ul>
 *   <li><b>算</b>只能由**评价行的拥有者**做——只有它能查 {@code order_review}；
 *   <li><b>写</b>只能由 **{@code provider} 表的拥有者**做——别的模块写它的表就是在破 ADR-0006。
 * </ul>
 *
 * 端口把这两半分在两侧，签名就是「把结论交给写方」：
 * 调用方（ph-order）算好平均分，实现方（ph-provider）只负责把它落到自己的表上。
 * 所以这里**只有两个参数、没有返回值**，也不引入任何 ph-api 的 DTO——
 * 形状简单到不需要在契约里立条目（与 {@code ConsultStats} / {@code BusinessNotification}
 * 那种「有形状就必须有契约出处」的情形不同）。
 *
 * <p><b>实现必须加 {@code @Primary}</b>：集成测试里有等价的桩（与 {@code ProviderAccessAdapter}
 * 同一处境），两个候选且无主时取用方会抛 {@code NoUniqueBeanDefinitionException}——
 * 正好把「有实现」变成 500，抵消这个端口的意义。
 *
 * <p>调用时机由调用方决定：ph-order 在**评价写入的同一个事务里**调它（评分与评价要一起成立，
 * 否则会出现「评价落库了、分数还是旧的」这种半成品），失败与评价一起回滚。
 */
public interface ProviderRatingApi {

    /**
     * 把这家门店的评分写成给定的值。
     *
     * @param providerId 服务者（门店）id
     * @param rating     **调用方算好的平均分**（一位小数，1–5）。传 {@code null} 视为无效输入，
     *                   实现直接忽略并留一行日志——评分是派生值，宁可不改也不要写进一个 null
     */
    void updateRating(long providerId, BigDecimal rating);
}
