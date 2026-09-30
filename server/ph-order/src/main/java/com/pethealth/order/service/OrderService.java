package com.pethealth.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pethealth.api.order.OrderCreateRequest;
import com.pethealth.api.order.OrderPhotoSlotRequest;
import com.pethealth.api.order.OrderPhotoWallView;
import com.pethealth.api.order.OrderSummaryView;
import com.pethealth.api.order.OrderView;
import com.pethealth.api.order.ProviderOrderSummaryView;
import com.pethealth.api.order.ProviderOrderView;
import com.pethealth.api.provider.ProviderServiceView;
import com.pethealth.api.record.ProviderReportArchiveApi;
import com.pethealth.catalog.api.CatalogPricingApi;
import com.pethealth.catalog.api.Price;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.error.ErrorCode;
import com.pethealth.common.time.AppTime;
import com.pethealth.common.trace.TraceIds;
import com.pethealth.common.util.Text;
import com.pethealth.order.domain.OrderPhotoSlot;
import com.pethealth.order.domain.OrderStatus;
import com.pethealth.order.domain.ServiceOrder;
import com.pethealth.order.mapper.ServiceOrderMapper;
import com.pethealth.privilege.api.CouponApi;
import com.pethealth.record.api.PetQueryApi;
import com.pethealth.api.reminder.BusinessMessageApi;
import com.pethealth.common.util.Masking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 订单：下单、接单、核销、报工、双侧取消（ADR-0038 第一节；到店付口径见 ADR-0036）。
 *
 * <p><b>状态机怎么落的</b>：每个迁移都是 {@code ServiceOrderMapper} 里一条带源状态的
 * 条件更新，受影响行数为 0 就是 40900（状态冲突）。本类从不 {@code setStatus} + {@code updateById}
 * ——那会把原子更新退化成「先查后写」，两个并发请求会双双通过（ADR-0044 在券额度上踩过的坑，
 * 核销的并发双击是同一个形状）。
 *
 * <p><b>一次下单做四件事，全在一个事务里</b>：① 复校价格区间（90001，与选品定价共用
 * {@link CatalogPricingApi} 一处实现）；② 检查时段可约（不在营业时间 / 已过去 → 40001）；
 * ③ 校验券（80001 / 80002）；④ 占用号源（40900）并锁定券。任何一步失败，前面做过的一起回滚
 * ——券的锁定与号源的占用都发生在同一个事务里，所以不会留下「占了位置却没成单」的残局。
 *
 * <p><b>没有的东西</b>：没有支付、没有退款单、没有「30 分钟未支付自动取消」、也没有
 * 定时任务去自动取消过期未核销的订单（ADR-0036 / ADR-0038）。钱在门店直接付给服务者。
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    /** 订单号 / 核销码的唯一键撞了就重来，最多这么多次（撞不代表出错，只代表换一个号）。 */
    private static final int CODE_ATTEMPTS = 6;

    /** 6 位核销码的取值范围（ADR-0038 第二节：可读出的 6 位数字，不是安全凭证）。 */
    private static final int REDEEM_CODE_BOUND = 1_000_000;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ServiceOrderMapper orderMapper;
    private final OrderAccess orderAccess;
    private final AppointmentSlotService slots;
    private final OrderPhotoWallService photoWall;
    private final OrderViews views;
    private final ProviderFacts providerFacts;
    private final OrderParties parties;
    private final CouponApi couponApi;
    private final CatalogPricingApi catalogPricing;
    private final PetQueryApi petQueryApi;
    private final OrderNoGenerator orderNos;
    private final BusinessMessageApi businessMessages;
    private final ProviderReportArchiveApi archiveReports;

    public OrderService(ServiceOrderMapper orderMapper, OrderAccess orderAccess, AppointmentSlotService slots,
                        OrderPhotoWallService photoWall, OrderViews views, ProviderFacts providerFacts,
                        OrderParties parties, CouponApi couponApi, CatalogPricingApi catalogPricing,
                        PetQueryApi petQueryApi, OrderNoGenerator orderNos,
                        BusinessMessageApi businessMessages, ProviderReportArchiveApi archiveReports) {
        this.orderMapper = orderMapper;
        this.orderAccess = orderAccess;
        this.slots = slots;
        this.photoWall = photoWall;
        this.views = views;
        this.providerFacts = providerFacts;
        this.parties = parties;
        this.couponApi = couponApi;
        this.catalogPricing = catalogPricing;
        this.petQueryApi = petQueryApi;
        this.orderNos = orderNos;
        this.businessMessages = businessMessages;
        this.archiveReports = archiveReports;
    }

    // ================================================================ C 端

    /**
     * 下单（契约 {@code POST /orders}）：占用号源 + 锁定券 + 区间复校。
     *
     * @throws BusinessException
     *         <ul>
     *           <li><b>40400</b>：宠物不属于我 / 服务项不存在或未上架；
     *           <li><b>40001</b>：预约日期已过去、时段不在营业时间内、时段已开始；
     *           <li><b>40900</b>：该时段已被预约（并发口径）；
     *           <li><b>80001 / 80002</b>（HTTP 200）：券不可用 / 券已核销；
     *           <li><b>90001</b>（HTTP 200）：服务者定价已被运营收窄到区间外。
     *         </ul>
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderView create(long userId, OrderCreateRequest request) {
        // ① 宠物归属：不属于我按不存在处理（40400），免得用 pet_id 探测别人有没有这只宠物
        if (!petQueryApi.existsOwnedBy(userId, request.petId())) {
            throw BusinessException.notFound("宠物不存在");
        }
        // ② 服务项（本店、已上架）与定价：查不到一律 40400
        ProviderServiceView service = providerFacts.requireListedService(
                request.providerId(), request.serviceId());
        BigDecimal price = Price.parse(service.price(), "服务项定价");
        // ③ 价格区间复校（ADR-0034 第五节）：运营收窄区间之后，存量服务项可能已经越界，
        //    越界就不能下单（90001 的 message 就是「价格须在¥X-¥Y之间」）
        catalogPricing.requirePriceInRange(service.serviceCode(), price);
        // ④ 时段可约（不在营业时间 / 已过去 → 40001）
        SlotGrid.Window window = slots.requireBookable(request.providerId(), request.serviceId(),
                request.appointmentDate(), request.startTime());
        // ⑤ 券校验（只读，不改状态）：不可用 80001，已核销 80002
        CouponApi.CouponCheck couponCheck = request.couponId() == null ? null
                : couponApi.check(request.couponId(), request.providerId(), service.serviceCode(), price);

        // ⑥ 宠物快照（订单是历史凭证，见迁移 V29 的注释）：档案侧查不到就留空，不让下单失败
        String petName = parties.petNames(List.of(request.petId())).getOrDefault(request.petId(), "");
        int petSpecies = parties.petSpecies(List.of(request.petId())).getOrDefault(request.petId(), 0);

        // ⑦ 占号源（满了 40900；行不存在则懒建一行）
        slots.occupy(request.providerId(), request.serviceId(), request.appointmentDate(), window);

        // ⑧ 建单
        ServiceOrder order = insert(userId, request, service, price, couponCheck, window, petName, petSpecies);

        // ⑨ 锁券：**锁定不是消耗**——核销时券才转「已用」，取消时释放回「待使用」（ADR-0038 第一节）。
        //    失败（被别的订单占用）会抛 80001，整个事务回滚，号源与订单一起撤销。
        if (couponCheck != null) {
            couponApi.lock(couponCheck.couponId(), order.getId(), userId);
        }
        log.info("下单成功：orderNo={} providerId={} serviceId={} slot={} {} couponId={}",
                order.getOrderNo(), order.getProviderId(), order.getServiceId(),
                order.getAppointmentDate(), order.getStartTime(), order.getCouponId());
        return views.mineView(order);
    }

    /** 我的订单（分页 + 状态筛选；按下单时间倒序）。 */
    public PageResult<OrderSummaryView> listMine(long userId, Integer status, long page, long pageSize) {
        IPage<ServiceOrder> result = orderMapper.selectPage(new Page<>(page, pageSize),
                new LambdaQueryWrapper<ServiceOrder>()
                        .eq(ServiceOrder::getUserId, userId)
                        .eq(status != null, ServiceOrder::getStatus, status)
                        .orderByDesc(ServiceOrder::getId));
        return PageResult.of(views.mineSummaries(result.getRecords()),
                result.getCurrent(), result.getSize(), result.getTotal());
    }

    /** 我的订单详情：**别人的订单一律 40400**（越权与不存在同码，免得用 id 探测）。 */
    public OrderView getMine(long userId, long orderId) {
        return views.mineView(orderAccess.requireMine(userId, orderId));
    }

    /**
     * 用户取消（契约 {@code POST /orders/{order_id}/cancel}）：按阶段分两种结果。
     *
     * <ul>
     *   <li><b>待接单</b>：直接取消 → 已取消，号源与券一并释放；
     *   <li><b>已预约</b>：只是**发起取消申请**（订单仍是已预约，等门店同意 / 拒绝）；
     *   <li><b>履约中</b>：不可取消 → 40900（只能报工完成，或由运营干预）；
     *   <li><b>已完成 / 已取消</b>：40900（终态）。
     * </ul>
     *
     * <p>没有超时自动取消（ADR-0038 第一节）：预约时间过了没核销不会自动取消——
     * 自动取消会让用户白跑一趟店。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderView cancelByUser(long userId, long orderId, String reason) {
        ServiceOrder order = orderAccess.requireMine(userId, orderId);
        if (order.getStatus() == OrderStatus.PENDING_ACCEPT) {
            cancel(order, userId, null, OrderStatus.CancelledBy.USER, reason);
        } else if (order.getStatus() == OrderStatus.BOOKED) {
            requestCancel(order, userId, reason);
        } else {
            throw notCancellable(order);
        }
        return views.mineView(orderAccess.requireMine(userId, orderId));
    }

    // ================================================================ 服务者侧

    /** 本店订单列表（分页 + 状态 / 日期筛选 + 核销定位关键字）。 */
    public PageResult<ProviderOrderSummaryView> listForProvider(long providerUserId, Integer status,
                                                               LocalDate appointmentDate, String keyword,
                                                               long page, long pageSize) {
        long providerId = providerFacts.requireProviderId(providerUserId);
        LambdaQueryWrapper<ServiceOrder> query = new LambdaQueryWrapper<ServiceOrder>()
                .eq(ServiceOrder::getProviderId, providerId)
                .eq(status != null, ServiceOrder::getStatus, status)
                .eq(appointmentDate != null, ServiceOrder::getAppointmentDate, appointmentDate)
                .orderByDesc(ServiceOrder::getId);
        String trimmed = keyword == null ? "" : keyword.trim();
        if (!trimmed.isEmpty()) {
            applyKeywordFilter(query, trimmed);
        }
        IPage<ServiceOrder> result = orderMapper.selectPage(new Page<>(page, pageSize), query);
        return PageResult.of(views.providerSummaries(result.getRecords()),
                result.getCurrent(), result.getSize(), result.getTotal());
    }

    /**
     * 本店订单详情。**没有核销码**：门店凭用户出示的码定位订单，不需要知道码本身
     * （知道了只会增加它被记下来复用的机会，ADR-0038 第二节）。
     */
    public ProviderOrderView getForProvider(long providerUserId, long orderId) {
        return views.providerView(requireOfProvider(providerUserId, orderId));
    }

    /**
     * 接单：待接单 → 已预约（契约 {@code POST /orders/{order_id}/accept}）。
     *
     * <p>接单即承诺：这个号源被这一单占住，而且**接单之后用户再取消需要门店同意**
     * （ADR-0038 第一节的取消规则按阶段分）。重复接单 / 别的状态 → 40900。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ProviderOrderView accept(long providerUserId, long orderId) {
        ServiceOrder order = requireOfProvider(providerUserId, orderId);
        int affected = orderMapper.markAccepted(orderId, order.getProviderId(),
                TraceIds.currentOperatorId(), TraceIds.currentTraceId(), AppTime.now());
        if (affected == 0) {
            throw conflict(order, "接单");
        }
        return views.providerView(requireOfProvider(providerUserId, orderId));
    }

    /**
     * 核销：已预约 → 履约中（契约 {@code POST /orders/{order_id}/redeem}）。
     *
     * <p>三条不变量（ADR-0038 第二节）：
     *
     * <ol>
     *   <li><b>原子的条件更新</b>：并发双击只有一个成功，另一个 40900——重复核销**不是幂等成功**，
     *       第二次核销是状态冲突；
     *   <li>券在**同一个事务**里转「已核销」：券的问题按券的码回（80001 / 80002），
     *       券的问题会让这一步整体回滚，订单退回「已预约」；
     *   <li><b>核销 ≠ 收款</b>：只确认履约开始，费用在门店直接付给服务者（ADR-0036）。
     * </ol>
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ProviderOrderView redeem(long providerUserId, long orderId) {
        ServiceOrder order = requireOfProvider(providerUserId, orderId);
        int affected = orderMapper.markRedeemed(orderId, order.getProviderId(),
                TraceIds.currentOperatorId(), TraceIds.currentTraceId(), AppTime.now());
        if (affected == 0) {
            throw conflict(order, "核销");
        }
        if (order.hasCoupon()) {
            // 券的核销只认「本店 + 本单」；已核销 → 80002，过期 / 不满足范围 → 80001，
            // 任何一个抛出都会让上面的状态更新一起回滚（同一个事务）
            couponApi.redeem(order.getCouponId(), order.getProviderId(), orderId,
                    TraceIds.currentOperatorId());
        }
        log.info("核销完成（与收款无关）：orderNo={} providerId={}", order.getOrderNo(), order.getProviderId());
        return views.providerView(requireOfProvider(providerUserId, orderId));
    }

    /**
     * 报工：履约中 → 已完成（契约 {@code POST /orders/{order_id}/report}）。
     *
     * <p>**三道照片墙是硬约束**（ADR-0040 第四节 / 交付文档 2.5 的验收标准「缺一道则系统拒绝报工」）：
     * 三个槽位各至少一张照片才允许提交，而且由**服务端**拒绝——前端把按钮禁掉不算数。
     * 被拒时 message 里**点名缺哪一道**（不能只说「报工失败」），客户端再从订单详情的
     * {@code photo_wall.missing_slots} 取同一份结论。
     *
     * <p>**报工成功即回写健康档案**（F004 的三源之一，ADR-0030 第四条）：订单是「服务发生过」的
     * 事实，档案是用户看得到的那条记录。两者在**同一个事务**里——订单转「已完成」而档案里没有
     * 这次服务，这种半成品查不出来（界面上的订单是对的），所以宁可让报工一起失败。
     * 档案那一行的形状（分项、业务日期）由 ph-record 决定（它拥有那张表，ADR-0006）。
     *
     * <p>待澄清（ADR-0048）：报工时刻取服务端当前时间（「平均服务时长」要由核销与报工两个时刻
     * 算出来，而 ADR 没说这两个时刻是手填还是自动记）。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ProviderOrderView report(long providerUserId, long orderId, String remark) {
        ServiceOrder order = requireOfProvider(providerUserId, orderId);
        if (order.getStatus() == null || order.getStatus() != OrderStatus.IN_SERVICE) {
            throw BusinessException.conflict("当前订单是「" + order.statusLabel() + "」，只有「履约中」才能报工");
        }
        List<Integer> missing = photoWall.missingSlots(orderId);
        if (!missing.isEmpty()) {
            String names = missing.stream().map(slot -> OrderPhotoSlot.slotName(slot))
                    .collect(Collectors.joining("、"));
            throw BusinessException.conflict("三道照片墙还缺：" + names + "，每道至少一张照片才能报工");
        }
        int affected = orderMapper.markReported(orderId, order.getProviderId(), remark,
                TraceIds.currentOperatorId(), TraceIds.currentTraceId(), AppTime.now());
        if (affected == 0) {
            throw conflict(order, "报工");
        }
        // 档案回流放在状态更新**之后**：抢输的并发报工在上一行已经 40900 出去了，
        // 不会走到这里，所以一个订单只会写出一条服务记录（重复报工不会写出第二条）
        archiveReports.recordServiceReport(new ProviderReportArchiveApi.ServiceReport(
                order.getPetId(), order.getUserId(), order.getServiceName(), remark));
        log.info("报工完成：orderNo={} providerId={}（已回写健康档案）",
                order.getOrderNo(), order.getProviderId());
        return views.providerView(requireOfProvider(providerUserId, orderId));
    }

    /**
     * 门店取消订单（契约 {@code POST /orders/{order_id}/cancel}）：待接单 / 已预约 → 已取消，
     * 号源与锁定的券一并释放，并给用户发一条**站内消息**。
     *
     * <p><b>理由必填</b>（ADR-0049 §七：允许服务者单方取消，但必须说明为什么，且计入考核过程分）：
     * 用户白跑一趟时至少要看到原因。留痕落在订单行上（{@code cancel_reason} + {@code cancelled_by=2}），
     * 考核那一波按「服务者单方取消数」算过程分（ADF-0049 §七 同步修订了 ADR-0039 §三）。
     *
     * <p><b>履约中不可取消</b>（只能报工完成，或由运营干预）→ 40900；已完成 / 已取消已经是终态 → 40900。
     * 没有退款单：钱在门店付，取消不产生任何资金动作（ADR-0036）。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ProviderOrderView cancelByProvider(long providerUserId, long orderId, String reason) {
        ServiceOrder order = requireOfProvider(providerUserId, orderId);
        if (reason == null || reason.isBlank()) {
            // 契约把这一条的 reason 写成可选（交付文档只对「拒绝取消申请」强制理由），
            // 而定稿口径要求必填：按契约优先的纪律，规则落在服务层而不是改契约的 required
            throw BusinessException.paramInvalid("门店取消订单必须填理由（会展示给用户）");
        }
        cancel(order, null, order.getProviderId(), OrderStatus.CancelledBy.PROVIDER, reason);
        // 用户侧收到站内消息（业务通知，ADR-0049 §七）：去重键按订单，重复取消不会发两遍
        businessMessages.notify(new BusinessMessageApi.BusinessNotification(
                order.getUserId(),
                BusinessMessageApi.TYPE_ORDER,
                "order-cancelled-" + orderId,
                "订单已取消",
                "门店取消了你的预约（" + order.getAppointmentDate() + " " + order.getStartTime()
                        + "）：" + reason.trim(),
                "查看订单",
                "/orders/" + orderId));
        return views.providerView(requireOfProvider(providerUserId, orderId));
    }

    /** 同意用户的取消申请：订单 → 已取消（号源与券释放）。没有待处理的申请 → 40900。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ProviderOrderView approveCancel(long providerUserId, long orderId) {
        ServiceOrder order = requireOfProvider(providerUserId, orderId);
        int affected = orderMapper.approveCancel(orderId, order.getProviderId(),
                OrderStatus.CancelledBy.PROVIDER, TraceIds.currentOperatorId(),
                TraceIds.currentTraceId(), AppTime.now());
        if (affected == 0) {
            throw cancelRequestConflict(order);
        }
        release(order);
        return views.providerView(requireOfProvider(providerUserId, orderId));
    }

    /**
     * 拒绝用户的取消申请（**必须填理由**，ADR-0038 第一节点名要求）：订单仍是「已预约」，
     * 门店要按约履约。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ProviderOrderView rejectCancel(long providerUserId, long orderId, String reason) {
        ServiceOrder order = requireOfProvider(providerUserId, orderId);
        int affected = orderMapper.rejectCancel(orderId, order.getProviderId(), reason,
                TraceIds.currentOperatorId(), TraceIds.currentTraceId(), AppTime.now());
        if (affected == 0) {
            throw cancelRequestConflict(order);
        }
        return views.providerView(requireOfProvider(providerUserId, orderId));
    }

    /**
     * 保存一个照片槽位（契约 {@code PUT /orders/{order_id}/photo-slots/{slot}}）：
     * **整体替换**，可多图 + 备注。
     *
     * <p>只在「履约中」可写（ADR-0040 第四节：订单进入履约中后开放）：待接单 / 已预约 /
     * 已完成 / 已取消 → 40900。**报工之后不能改**（契约按最保守形态写：要改只能走运营干预）。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderPhotoWallView savePhotoSlot(long providerUserId, long orderId, int slot,
                                           OrderPhotoSlotRequest request) {
        ServiceOrder order = requireOfProvider(providerUserId, orderId);
        if (!OrderPhotoSlot.isValidSlot(slot)) {
            throw BusinessException.paramInvalid("照片槽位只能是 1 接宠检查 / 2 服务防护 / 3 取宠对比");
        }
        if (order.getStatus() == null || order.getStatus() != OrderStatus.IN_SERVICE) {
            if (order.getStatus() != null && order.getStatus() == OrderStatus.COMPLETED) {
                // 报工提交即固化（ADR-0049 §一）：用 40901 与「还没到能写的状态」分开，
                // 前端不必靠 message 猜是哪一种；修正只有运营干预一条路（落在 admin 域）
                throw new BusinessException(ErrorCode.ORDER_FINALIZED,
                        "订单已报工完成，照片与备注不能再改（要修正只能走运营干预）");
            }
            throw BusinessException.conflict("照片墙只在「履约中」开放，当前订单是「"
                    + order.statusLabel() + "」");
        }
        photoWall.replace(orderId, order.getPetId(), slot, request);
        return photoWall.viewOf(orderId);
    }

    // ================================================================ 内部

    /**
     * 真正写入订单。订单号与核销码都有唯一键，撞了就换一个重来。
     *
     * <p>为什么敢在事务里重试：MySQL 的重复键错误只回滚**那一条语句**，把异常接住之后
     * 事务仍然可用（券的额度那边也是同一套写法）。唯一的键撞上说明另一个请求刚用了同一个
     * 6 位码——换一个即可，不该让用户看到「下单失败」。
     */
    private ServiceOrder insert(long userId, OrderCreateRequest request, ProviderServiceView service,
                               BigDecimal price, CouponApi.CouponCheck couponCheck,
                               SlotGrid.Window window, String petName, int petSpecies) {
        BigDecimal discount = couponCheck == null || couponCheck.faceValue() == null
                ? BigDecimal.ZERO.setScale(2) : couponCheck.faceValue();
        // 「预估实付」是展示值，但**不能是负数**：券面额大于总额时按 0 收口
        // （这个取舍写在 ADR-0048；契约只给了公式，没写面额大于总额时怎么办）
        BigDecimal estimated = price.subtract(discount).max(BigDecimal.ZERO).setScale(2);
        for (int attempt = 0; attempt < CODE_ATTEMPTS; attempt++) {
            String redeemCode = randomRedeemCode();
            ServiceOrder order = new ServiceOrder();
            order.setOrderNo(orderNos.next());
            order.setUserId(userId);
            order.setPetId(request.petId());
            order.setPetName(petName);
            order.setPetSpecies(petSpecies);
            order.setProviderId(request.providerId());
            order.setServiceId(request.serviceId());
            order.setServiceCode(service.serviceCode());
            order.setServiceName(service.serviceName() == null ? "" : service.serviceName());
            order.setAppointmentDate(request.appointmentDate());
            order.setStartTime(window.startTime());
            order.setEndTime(window.endTime());
            order.setTotalAmount(price);
            order.setCouponId(couponCheck == null ? null : couponCheck.couponId());
            order.setCouponDiscount(discount);
            order.setEstimatedPayAmount(estimated);
            order.setStatus(OrderStatus.PENDING_ACCEPT);
            order.setRedeemCode(redeemCode);
            order.setRedeemCodeActive(redeemCode);
            order.setRemark(Text.trimToNull(request.remark()));
            try {
                orderMapper.insert(order);
                return order;
            } catch (DuplicateKeyException e) {
                log.info("订单号或核销码撞了唯一键，换一个重来（第 {} 次）：{}", attempt + 1, e.getMessage());
            }
        }
        throw new BusinessException(ErrorCode.SERVER_ERROR, "订单号生成失败，请重试");
    }

    /** 取消一个**未结束**的订单：状态条件更新 + 释放号源与券。 */
    private void cancel(ServiceOrder order, Long scopeUserId, Long scopeProviderId,
                        int cancelledBy, String reason) {
        int affected = orderMapper.cancel(order.getId(), scopeUserId, scopeProviderId, cancelledBy,
                OrderStatus.CancelRequest.APPROVED, Text.trimToNull(reason),
                TraceIds.currentOperatorId(), TraceIds.currentTraceId(), AppTime.now());
        if (affected == 0) {
            throw notCancellable(order);
        }
        release(order);
    }

    /**
     * 释放这一单占用的资源：号源 {@code -1}、锁定的券放回「待使用」。
     *
     * <p>只有真正推进了取消的那一个请求会调到这里（取消是条件更新），所以不需要额外的去重。
     * 券的释放条件里带着「被本订单锁定」，所以重复释放也不会误放别人的锁。
     */
    private void release(ServiceOrder order) {
        slots.release(order.getProviderId(), order.getServiceId(),
                order.getAppointmentDate(), order.getStartTime());
        if (order.hasCoupon()) {
            couponApi.release(order.getCouponId(), order.getId());
        }
    }

    /** 用户发起取消申请（已预约阶段）。重复申请 / 状态已变 → 40900。 */
    private void requestCancel(ServiceOrder order, long userId, String reason) {
        int affected = orderMapper.requestCancel(order.getId(), userId, Text.trimToNull(reason),
                TraceIds.currentOperatorId(), TraceIds.currentTraceId(), AppTime.now());
        if (affected == 0) {
            ServiceOrder latest = orderMapper.selectById(order.getId());
            if (latest != null && latest.hasPendingCancelRequest()) {
                throw BusinessException.conflict("已经提交过取消申请，等门店处理（无需重复提交）");
            }
            throw conflict(latest == null ? order : latest, "申请取消");
        }
    }

    /** 「当前状态不能取消」的答复：说清现在是什么状态，别只说「冲突」。 */
    private static BusinessException notCancellable(ServiceOrder order) {
        if (order.getStatus() != null && order.getStatus() == OrderStatus.IN_SERVICE) {
            return BusinessException.conflict("「履约中」不能取消：服务已开始，只能报工完成或由平台介入");
        }
        if (order.isTerminal()) {
            return BusinessException.conflict("订单已经是「" + order.statusLabel() + "」，不能再取消");
        }
        return conflict(order, "取消");
    }

    /** 取消申请的同意 / 拒绝失败时的答复：没有待处理的申请，或订单状态已变。 */
    private static BusinessException cancelRequestConflict(ServiceOrder order) {
        if (!order.hasPendingCancelRequest()) {
            return BusinessException.conflict("当前没有待处理的取消申请");
        }
        return conflict(order, "处理取消申请");
    }

    /** 迁移被状态挡住时的统一答复（40900，不是 40001——这是状态冲突，不是参数写错）。 */
    private static BusinessException conflict(ServiceOrder order, String action) {
        return BusinessException.conflict("当前订单是「" + order.statusLabel() + "」，不能" + action);
    }

    /** 本店订单；不是本店的按不存在处理（40400）。顺手解析出服务者身份，后面的迁移都用它。 */
    private ServiceOrder requireOfProvider(long providerUserId, long orderId) {
        long providerId = providerFacts.requireProviderId(providerUserId);
        ServiceOrder order = orderMapper.selectById(orderId);
        if (order == null || order.getProviderId() == null || order.getProviderId() != providerId) {
            throw BusinessException.notFound("订单不存在");
        }
        return order;
    }

    /**
     * 核销定位的三个入口（ADR-0038 第二节）：**订单号按包含匹配**（用户报单号常常只报后几位）、
     * **手机号与 6 位核销码按精确匹配**，三者取并集——门店只会输入一个关键字，
     * 让平台去猜它是什么，比让门店先选「按什么搜」更省事。
     *
     * <p>手机号是密文 + 等值索引（ADR-0013），只能经 ph-account 的接口换算成 user_id；
     * 手机号解析不出来（或压根不像手机号）时不影响另外两个入口。
     */
    private void applyKeywordFilter(LambdaQueryWrapper<ServiceOrder> query, String keyword) {
        Long userId = null;
        if (looksLikePhone(keyword)) {
            userId = parties.userIdByPhone(keyword).orElse(null);
            // 审计留痕（ADR-0049 §二：核销允许按完整手机号等值查询，但要计入审计）。
            // **日志里只出现脱敏号**：查得到不等于看得到，明文只在 ph-account 的哈希比对里存在。
            // 落成 audit_log 那一行需要 ph-account 的写侧接口（本切片报为「需要协调」，见 ADR-0048）
            log.info("核销按手机号定位订单：operator={} traceId={} phone={} providerId(未解析)命中={}",
                    TraceIds.currentOperatorId(), TraceIds.currentTraceId(),
                    Masking.phone(keyword), userId != null);
        }
        final Long matchedUserId = userId;
        query.and(wrapper -> {
            wrapper.eq(ServiceOrder::getRedeemCode, keyword);
            wrapper.or().like(ServiceOrder::getOrderNo, keyword);
            if (matchedUserId != null) {
                wrapper.or().eq(ServiceOrder::getUserId, matchedUserId);
            }
        });
    }

    /** 11 位、以 1 开头：只有这种形状才值得去查手机号哈希。 */
    private static boolean looksLikePhone(String keyword) {
        return keyword.length() == 11 && keyword.charAt(0) == '1'
                && keyword.chars().allMatch(Character::isDigit);
    }

    /** 6 位核销码：补零到 6 位（{@code 000123} 也是合法的码，用户照着念就行）。 */
    private static String randomRedeemCode() {
        return String.format("%06d", RANDOM.nextInt(REDEEM_CODE_BOUND));
    }
}
