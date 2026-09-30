package com.pethealth.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pethealth.api.order.OrderReviewRequest;
import com.pethealth.api.order.OrderReviewView;
import com.pethealth.api.provider.ProviderRatingApi;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.trace.TraceIds;
import com.pethealth.common.util.Text;
import com.pethealth.order.domain.OrderReview;
import com.pethealth.order.domain.OrderStatus;
import com.pethealth.order.domain.ServiceOrder;
import com.pethealth.order.event.OrderReviewedEvent;
import com.pethealth.order.mapper.OrderReviewMapper;
import com.pethealth.order.mapper.ServiceOrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * 评价晒单（第一版：评分 + 一句话）。
 *
 * <p><b>路径与归属</b>：本服务同时服务两条接口——
 *
 * <ul>
 *   <li>{@code POST /api/v1/app/orders/{order_id}/review}（写，路径在订单域）；
 *   <li>{@code GET /api/v1/app/providers/{provider_id}/reviews}（读，路径在门店域）。
 * </ul>
 *
 * 第二条虽然挂在门店（服务者）域的路径下，**数据归订单域**（{@code order_review} 是 ph-order 的表），
 * 所以由本模块来服务它。这是刻意的取舍：**路径按调用方语义，数据按归属**。
 * 反过来做（把表挪给 ph-provider）会让「一单一评」这个约束依赖订单表——
 * 而那正是 ADR-0006 禁止 ph-provider 碰的东西。
 *
 * <p>三条规则，逐条对应一个答复（口径写在契约 {@code POST /orders/{order_id}/review} 里）：
 *
 * <ol>
 *   <li><b>只能评自己的订单</b>：不是我的按不存在处理 → <b>40400</b>（越权与不存在同码，
 *       免得用 order_id 探测别人的订单）；
 *   <li><b>只有「已完成」能评</b>：其余状态 → <b>40900</b>，message 里说清当前是什么状态；
 *   <li><b>一单一评</b>：先查（给调用方一个明确的答复）+ 唯一键兜底（并发双击也只成一条）。
 *       重复提交**不是幂等成功**而是 <b>40900</b>——与「重复核销 40900」同一条纪律：
 *       幂等返回会让「我明明只评了一次」这种疑问无从解释。
 * </ol>
 *
 * <p>评价成功还会带动两件事，各有各的失败口径：
 *
 * <ul>
 *   <li><b>重算门店评分</b>并回写 {@code provider.rating}（经 {@link ProviderRatingApi}）——
 *       在**同一个事务**里：评价落库而分数还是旧的，这种半成品在界面上看不出来
 *       （评价显示出来了、门店页还是旧分），所以宁可一起失败（与报工回写档案同一个取舍）；
 *   <li><b>发 REVIEW 行为分</b>——**不在事务里**，走 AFTER_COMMIT 事件（见
 *       {@link OrderReviewPointsListener}）：发分失败不得让评价失败。
 * </ul>
 */
@Service
public class OrderReviewService {

    private static final Logger log = LoggerFactory.getLogger(OrderReviewService.class);

    /** 门店评分的定标：`provider.rating` 是 DECIMAL(3,1)，所以平均分保留一位小数。 */
    private static final int RATING_SCALE = 1;

    private final OrderReviewMapper reviewMapper;
    private final ServiceOrderMapper orderMapper;
    private final ProviderRatingApi providerRatingApi;
    private final ApplicationEventPublisher eventPublisher;

    public OrderReviewService(OrderReviewMapper reviewMapper, ServiceOrderMapper orderMapper,
                              ProviderRatingApi providerRatingApi,
                              ApplicationEventPublisher eventPublisher) {
        this.reviewMapper = reviewMapper;
        this.orderMapper = orderMapper;
        this.providerRatingApi = providerRatingApi;
        this.eventPublisher = eventPublisher;
    }

    // ---------------------------------------------------------------- 写

    /**
     * 提交一条评价（契约 {@code POST /orders/{order_id}/review}）。
     *
     * @throws BusinessException
     *         <ul>
     *           <li><b>40400</b>：订单不存在，或不是我的；
     *           <li><b>40900</b>：订单不在「已完成」，或这一单已经评价过；
     *           <li><b>40001</b>：评分不在 1–5（接口层的校验注解已拦一道，这里是领域的最后一道）。
     *         </ul>
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderReviewView create(long userId, long orderId, OrderReviewRequest request) {
        if (!OrderReview.isValidRating(request.rating())) {
            // 契约与 DTO 上的 @Min/@Max 已经在接口层拦过：走到这里说明有路径绕过了校验
            throw BusinessException.paramInvalid("评分只能是 1–5 星");
        }
        ServiceOrder order = requireMine(userId, orderId);
        if (order.getStatus() == null || order.getStatus() != OrderStatus.COMPLETED) {
            throw BusinessException.conflict("当前订单是「" + order.statusLabel()
                    + "」，只有「已完成」的订单可以评价");
        }
        if (reviewMapper.countByOrder(orderId) > 0) {
            throw alreadyReviewed();
        }

        OrderReview review = new OrderReview();
        review.setOrderId(orderId);
        review.setUserId(userId);
        review.setPetId(order.getPetId());
        review.setProviderId(order.getProviderId());
        review.setRating(request.rating());
        // 空串按未填处理：存 null 而不是空字符串，「没写」在数据上只有一个表示
        review.setContent(Text.trimToNull(request.content()));
        try {
            reviewMapper.insert(review);
        } catch (DuplicateKeyException e) {
            // 并发双击：唯一键是最后一道。先查那一步在并发下会双双通过，键不会
            throw alreadyReviewed();
        }

        // 门店评分是评价的派生值，与评价在同一个事务里（见类注释）
        recalculateProviderRating(order.getProviderId());

        // 发分不在事务里：事件在提交之后才被消费（见 OrderReviewPointsListener）
        eventPublisher.publishEvent(new OrderReviewedEvent(userId, orderId));
        log.info("评价提交成功：orderNo={} providerId={} rating={} 有文字={}",
                order.getOrderNo(), order.getProviderId(), request.rating(),
                review.getContent() != null);
        return toView(review);
    }

    // ---------------------------------------------------------------- 读

    /**
     * 某门店的评价列表（契约 {@code GET /providers/{provider_id}/reviews}）：最近提交的在前。
     *
     * <p>**不做门店可见性判定**（不判「这家店可不可浏览」）：那套口径属于 ph-provider 的浏览面，
     * 而这条读路径由订单域服务。不可浏览的店，它的详情页本身就打不开，用户到不了这个区块。
     * 与之对应：不存在的门店返回 200 + 空列表，不是 40400。
     *
     * <p>要身份（「内容面」，见契约）：游客只给「浏览服务」，评价是用户写下的内容。
     * 这一条由 {@code JwtAuthenticationFilter} 的统一规则兜住——本方法不自己判身份，
     * 因为那会把「要不要登录」变成两处各判一次。
     */
    @Transactional(readOnly = true)
    public PageResult<OrderReviewView> listOfProvider(long providerId, long page, long pageSize) {
        IPage<OrderReview> result = reviewMapper.selectPage(new Page<>(page, pageSize),
                new LambdaQueryWrapper<OrderReview>()
                        .eq(OrderReview::getProviderId, providerId)
                        .orderByDesc(OrderReview::getCreatedAt)
                        .orderByDesc(OrderReview::getId));
        List<OrderReviewView> views = result.getRecords().stream().map(OrderReviewService::toView).toList();
        return PageResult.of(views, result.getCurrent(), result.getSize(), result.getTotal());
    }

    /**
     * 某订单的评价（订单详情用它显示「已评价」）。没有时返回空。
     *
     * <p>用 BaseMapper 的方法查，所以 {@code is_deleted = 0} 由框架自动带上——
     * 手写 SQL 时那一条要自己写（见 {@code OrderReviewMapper}）。
     * {@code LIMIT 1} 是给「一单一评」这条约束兜底：唯一键保证不会有两行，
     * 但查询也不该假设「库里的约束一定还在」。
     */
    @Transactional(readOnly = true)
    public Optional<OrderReviewView> findOfOrder(long orderId) {
        OrderReview review = reviewMapper.selectOne(new LambdaQueryWrapper<OrderReview>()
                .eq(OrderReview::getOrderId, orderId)
                .last("LIMIT 1"));
        return Optional.ofNullable(review).map(OrderReviewService::toView);
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 重算并回写门店评分：**平均分在这里算**（评价行归本模块），写回由 ph-provider 做
     * （{@code provider} 表归它）。一单都没有时不做任何事——那家店的 rating 保持数据库默认值，
     * 我们不把默认值「算」成 0 分。
     */
    private void recalculateProviderRating(long providerId) {
        BigDecimal average = reviewMapper.averageRating(providerId);
        if (average == null) {
            // 理论上走不到（刚插入了一条），但真走到了也不该把一个 null 写进去
            log.warn("门店没有任何评价，跳过评分回写：providerId={}", providerId);
            return;
        }
        providerRatingApi.updateRating(providerId, average.setScale(RATING_SCALE, RoundingMode.HALF_UP));
    }

    /** 我的订单；不是我的按不存在处理（40400，与 {@code OrderService.getMine} 同一口径）。 */
    private ServiceOrder requireMine(long userId, long orderId) {
        ServiceOrder order = orderMapper.selectById(orderId);
        if (order == null || order.getUserId() == null || order.getUserId() != userId) {
            throw BusinessException.notFound("订单不存在");
        }
        return order;
    }

    private static BusinessException alreadyReviewed() {
        return BusinessException.conflict("这一单已经评价过了（一单一评，不能改也不能追评）");
    }

    static OrderReviewView toView(OrderReview review) {
        return new OrderReviewView(review.getId(), review.getOrderId(), review.getProviderId(),
                review.getRating(), review.getContent(), review.getCreatedAt());
    }
}
