package com.pethealth.provider.service;

import com.pethealth.api.provider.ProviderRatingApi;
import com.pethealth.common.time.AppTime;
import com.pethealth.common.trace.TraceIds;
import com.pethealth.provider.mapper.ProviderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * {@link ProviderRatingApi} 的实现：把订单域算好的平均分写进 {@code provider.rating}。
 *
 * <p><b>为什么「算」在 ph-order、「写」在这里</b>：评分是评价的派生值，评价行
 * （{@code order_review}）归 ph-order；而 {@code provider.rating} 是本模块的表。
 * ADR-0006 两头都管：ph-order 不能写 provider 表，本模块不能读 order_review。
 * 所以端口只能把结论交过来——接口的两个参数就是这条分工的形状
 * （见 {@code ProviderRatingApi} 的类注释）。
 *
 * <p>三条实现细节：
 *
 * <ul>
 *   <li><b>只写 rating 这一列</b>，而且是**手写的一条 UPDATE**（{@code ProviderMapper.updateRating}）：
 *       拿一个「只 set 了 rating」的实体去 {@code updateById} 会把门店的营业时间、简介与坐标
 *       一起写成 NULL（那几个字段是 {@code FieldStrategy.ALWAYS}），
 *       结果就是这家店「当天不营业」——评价切片的集成测试逮到的正是这个；
 *   <li><b>审计列在 SQL 里显式写</b>（{@code updated_by} / {@code trace_id} / {@code updated_at}）：
 *       手写 SQL 不走框架的自动填充，而「写操作留痕」是硬约束；
 *   <li><b>事务用默认的 REQUIRED</b>：调用方（ph-order 的评价事务）已经有事务时加入它——
 *       这正是「评价落库与评分回写一起成立」的实现方式（与报工回写档案同一条取舍）。
 *       单独调用时它自己起一个事务，行为不变；写成 MANDATORY 能更早发现调用方丢了事务边界，
 *       但本仓既有的跨模块写（{@code ProviderReportArchiveApi} 的实现）用的是 REQUIRED，
 *       这里不为一个假想的调用方多留一道会抛异常的规矩。
 * </ul>
 *
 * <p>{@code @Primary} 的理由与 {@link ProviderAccessAdapter} 完全相同：集成测试里有等价的桩，
 * 两个候选且无主时取用方（ph-order 的构造器注入）会抛 {@code NoUniqueBeanDefinitionException}，
 * 把「有实现」变成 500——正好抵消这个端口的意义。
 */
@Component
@Primary
public class ProviderRatingAdapter implements ProviderRatingApi {

    private static final Logger log = LoggerFactory.getLogger(ProviderRatingAdapter.class);

    private final ProviderMapper providerMapper;

    public ProviderRatingAdapter(ProviderMapper providerMapper) {
        this.providerMapper = providerMapper;
    }

    @Override
    @Transactional
    public void updateRating(long providerId, BigDecimal rating) {
        if (rating == null) {
            // 派生值宁可不改也不要写进一个 null：NULL 会让 C 端的排序与展示都退回默认值，
            // 而「退回默认值」与「真的没有评价」在数据上就分不出来了
            log.warn("评分回写收到空值，已忽略：providerId={}", providerId);
            return;
        }
        int affected = providerMapper.updateRating(providerId, rating, TraceIds.currentOperatorId(),
                TraceIds.currentTraceId(), AppTime.now());
        if (affected == 0) {
            // 门店不存在（或已被软删）：不回滚调用方的主流程——评价本身是用户的事实，
            // 不该因为聚合目标不见了而丢掉（与「发分失败不让评价失败」同一条取舍）
            log.warn("评分回写找不到门店（可能已删除），评价照常：providerId={} rating={}", providerId, rating);
            return;
        }
        log.info("门店评分已回写：providerId={} rating={}", providerId, rating);
    }
}
