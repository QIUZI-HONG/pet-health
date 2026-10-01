package com.pethealth.privilege.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.error.ErrorCode;
import com.pethealth.catalog.api.CatalogQueryApi;
import com.pethealth.common.time.AppTime;
import com.pethealth.common.util.Text;
import com.pethealth.api.privilege.CouponDtos;
import com.pethealth.api.privilege.CouponFactsApi;
import com.pethealth.privilege.api.CouponApi;
import com.pethealth.privilege.domain.Coupon;
import com.pethealth.privilege.domain.CouponContribution;
import com.pethealth.privilege.domain.CouponTemplate;
import com.pethealth.privilege.mapper.CouponContributionMapper;
import com.pethealth.privilege.mapper.CouponMapper;
import com.pethealth.privilege.mapper.CouponTemplateMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 券实例的生命周期：**发、校验、锁定、释放、核销**（切片 #110 的写侧）。
 *
 * <p>两个决定贯穿这个类：
 *
 * <ol>
 *   <li><b>额度不超发靠「锁行 + 实时算」</b>：服务者成本券在发放前先
 *       {@code SELECT ... FOR UPDATE} 锁住那条贡献，再算「还能不能发」。
 *       只靠先查后写（check-then-act）在并发下必超发——两个请求同时读到「还剩 1 张」。
 *       平台补贴券没有服务者额度，但有一条发放上限（{@code issue_limit}）同样在事务里判；
 *   <li><b>同一行为不重复发奖靠唯一键</b>：{@code (source, source_ref)} 唯一，
 *       重复调用返回首次那张券（幂等），而不是又发一张。靠数据库而不是靠调用方小心。
 * </ol>
 *
 * <p>核销是**原子的条件更新**（ADR-0038 第二节）：并发双击只有一个成功，另一个拿 80002。
 * 核销 ≠ 收款：这一步只确认券被用了，钱在门店付（ADR-0036）。
 *
 * <p>{@code @Primary} 是为了 {@link CouponFactsApi}：集成测试里有一个等价的桩
 * （{@code OrderTestSupport.StubCrossModuleApis}），取用方用 {@code ObjectProvider} 就地取实现
 * ——两个候选且无主时会抛 {@code NoUniqueBeanDefinitionException}（与
 * {@code ProviderAccessAdapter} 同一处取舍）。
 */
@Service
@Primary
public class CouponService implements CouponApi, CouponFactsApi {

    private static final Logger log = LoggerFactory.getLogger(CouponService.class);

    /**
     * 「用户显式占用、还没落到订单上」的占位持有者。
     *
     * <p>券包页的「用这张券」（{@code POST /coupons/{id}/lock}）由用户自己发起，那一刻还没有订单，
     * 但 {@code locked_order_id} 是 NOT NULL 的订单引用列。用 0 当占位：它不是任何订单 id
     * （订单 id 从 1 开始），语义是「这张券被它的主人先占住了」。下单时会换成真实订单 id，
     * 取消时也只释放真实订单持有的那些（条件里带着订单 id）。
     */
    public static final long STANDALONE_HOLDER = 0L;

    /** 券码字符集：去掉容易看错的 0/O/1/I，券码是给人念、给人抄的。 */
    private static final char[] CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final int CODE_LENGTH = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CouponMapper couponMapper;
    private final CouponTemplateMapper templateMapper;
    private final CouponContributionMapper contributionMapper;
    private final CatalogQueryApi catalog;

    public CouponService(CouponMapper couponMapper, CouponTemplateMapper templateMapper,
                         CouponContributionMapper contributionMapper, CatalogQueryApi catalog) {
        this.couponMapper = couponMapper;
        this.templateMapper = templateMapper;
        this.contributionMapper = contributionMapper;
        this.catalog = catalog;
    }

    // ---------------------------------------------------------------- 发放

    /**
     * {@inheritDoc}
     *
     * <p>服务者成本券的额度从哪条贡献出：**按贡献 id 升序取第一条还有可发放余量的**
     * （先承诺的先消耗）。这条规则是刻意选的「可预期」：服务者能算出自己的额度大概什么时候用完，
     * 而不是被一个随机分摊算法弄得看不出规律（取舍与待澄清见 ADR-0044）。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CouponInfo issue(IssueCommand command) {
        // 先锁模板行，再算额度。**顺序与隔离级别都不是随手写的**：
        //   - 额度是「读出来的」（已核销 + 占用中），所以必须让这一串读看到**最新已提交**的数据。
        //     在 MySQL 默认的 REPEATABLE READ 下，事务第一次一致性读就定了快照，
        //     于是「先读模板 → 再锁贡献 → 再数券」会数到别人还没提交的那一版 —— 实测会超发
        //     （16 个并发请求成功 14 个，承诺额度只有 5）。取 READ COMMITTED 后每条语句都是新快照，
        //     配合下面持有的行锁，串行关系才成立；
        //   - 锁模板行（当前读）一举两得：平台补贴券的发放上限没有别的锁可依靠，
        //     而且它不会建立快照，后面的计数自然是最新的。
        CouponTemplate template = templateMapper.selectForUpdate(command.templateId());
        if (template == null || !template.isEnabled()) {
            throw BusinessException.notFound("券模板不存在或已停用");
        }
        LocalDateTime now = AppTime.now();

        // 幂等：同一来源的同一引用只发一张（重放、重试、批算重跑都命在这里）
        if (command.sourceRef() != null && !command.sourceRef().isBlank()) {
            Coupon existing = couponMapper.selectOne(Wrappers.<Coupon>lambdaQuery()
                    .eq(Coupon::getSource, command.source())
                    .eq(Coupon::getSourceRef, command.sourceRef()));
            if (existing != null) {
                log.info("同一来源引用重复发券，返回已发的那一张：source={} ref={} couponId={}",
                        command.source(), command.sourceRef(), existing.getId());
                return toInfo(existing, template);
            }
        }

        CouponContribution contribution = null;
        if (template.isPlatformSubsidy()) {
            if (command.contributionId() != null) {
                throw BusinessException.paramInvalid("平台补贴券不占用服务者的贡献额度");
            }
            requireWithinIssueLimit(template);
        } else {
            contribution = pickContribution(template, command.contributionId());
        }

        Coupon coupon = new Coupon();
        coupon.setCode(nextCode());
        coupon.setUserId(command.userId());
        coupon.setTemplateId(template.getId());
        coupon.setSource(command.source());
        coupon.setSourceRef(Text.trimToNull(command.sourceRef()));
        coupon.setContributionId(contribution == null ? null : contribution.getId());
        coupon.setProviderId(contribution == null ? null : contribution.getProviderId());
        coupon.setStatus(Coupon.STATUS_UNUSED);
        PrivilegeViews.copySnapshot(template, coupon, now);

        try {
            couponMapper.insert(coupon);
        } catch (DuplicateKeyException e) {
            // 并发下的同一来源引用：唯一键兜底，回读已发的那一张
            Coupon existing = couponMapper.selectOne(Wrappers.<Coupon>lambdaQuery()
                    .eq(Coupon::getSource, command.source())
                    .eq(Coupon::getSourceRef, command.sourceRef()));
            if (existing != null) {
                return toInfo(existing, template);
            }
            throw e;
        }
        return toInfo(coupon, template);
    }

    /**
     * 挑一条还有可发放余量的贡献（并锁住它）。
     *
     * <p>这里**必须**是 {@code FOR UPDATE}：锁住之后「算余量 → 插入券」才是原子的。
     * 不同服务者的贡献互不影响，所以锁粒度是「服务者 × 模板」而不是整张表。
     */
    private CouponContribution pickContribution(CouponTemplate template, Long contributionId) {
        LocalDateTime now = AppTime.now();
        List<CouponContribution> candidates;
        if (contributionId != null) {
            // 指定了贡献：只认这一条（运营手动干预或测试用），其余规则一致。
            // 注意 selectForUpdate 可能返回 null（id 不存在），所以这里先取出来再判——
            // 直接 List.of(null) 会 NPE，那会让一次「额度用完了」变成 50000
            CouponContribution specified = contributionMapper.selectForUpdate(contributionId);
            if (specified == null) {
                throw BusinessException.notFound("指定的券贡献不存在");
            }
            candidates = List.of(specified);
        } else {
            candidates = contributionMapper.selectList(Wrappers.<CouponContribution>lambdaQuery()
                        .eq(CouponContribution::getTemplateId, template.getId())
                        .eq(CouponContribution::getStatus, CouponContribution.STATUS_ACTIVE)
                        .orderByAsc(CouponContribution::getId)
                        .last("FOR UPDATE"));
        }
        for (CouponContribution contribution : candidates) {
            if (contribution == null) {
                continue;
            }
            CouponQuota quota = CouponQuota.of(contribution,
                    couponMapper.statOfContribution(contribution.getId(), now));
            if (quota.available() > 0) {
                return contribution;
            }
        }
        throw BusinessException.conflict("该券的可发放额度已用完（服务者可承诺的额度用尽，过期未核销的券会释放额度回池）");
    }

    /** 平台补贴券的发放上限（没有服务者额度，所以这条上限是唯一的闸）。 */
    private void requireWithinIssueLimit(CouponTemplate template) {
        if (template.getIssueLimit() == null) {
            return;
        }
        int issued = couponMapper.countIssuedByTemplate(template.getId());
        if (issued >= template.getIssueLimit()) {
            throw BusinessException.conflict("该平台补贴券的发放上限已用完（上限 "
                    + template.getIssueLimit() + " 张）");
        }
    }

    // ---------------------------------------------------------------- 运营侧

    /**
     * 运营定向发放平台补贴券（「平台补贴」这一来源的真实产出路径）。
     *
     * <p>**只发平台补贴券**：服务者成本的券走服务者的贡献额度，平台不能替服务者放券——
     * 那等于平台替服务者承诺了额度，而额度是服务者自己的经营决定。
     *
     * <p>不去重（同一用户同一模板可以发多张）：重复发放是运营看得见的动作，
     * 不做隐式去重；要不要限领一张见 ADR-0044 的待澄清。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CouponInfo issueSubsidyCoupon(long userId, long templateId, String remark) {
        CouponTemplate template = templateMapper.selectForUpdate(templateId);
        if (template == null || !template.isEnabled()) {
            throw BusinessException.notFound("券模板不存在或已停用");
        }
        if (!template.isPlatformSubsidy()) {
            throw BusinessException.conflict("只有平台补贴券可以定向发放；服务者成本的券由服务者的贡献额度决定");
        }
        return issue(new IssueCommand(userId, templateId, Coupon.SOURCE_PLATFORM_SUBSIDY,
                remark == null || remark.isBlank() ? null : "subsidy:" + remark.trim(), null, remark));
    }

    // ---------------------------------------------------------------- 订单侧

    /**
     * 按 id 批量取券实例视图（{@link CouponFactsApi}）：订单详情要渲染「本单锁定的那张券」。
     *
     * <p>为什么与 {@link CouponApi} 分开两件事：写动作需要的只是「能不能用」的那个子集
     * （{@code CouponInfo}），而用户看的那张卡片要的是面额、门槛、有效期、状态与核销时间。
     * 拿写侧的子集去拼卡片，缺的正好是用户最关心的两个字段。
     *
     * <p>装配走 {@link PrivilegeViews#toCouponView}——与 C 端「我的券」、服务者券列表
     * **同一份**，所以三处对同一个券的渲染不会分叉。模板用一次批量查询取回，不是每张券查一次。
     *
     * <p>{@code providerName}（核销门店）在这里给 {@code null}：那一格是「把券当某店的券来展示」
     * 时才需要（券包、贡献列表），而订单卡片上的门店来自订单本身——同一张卡片上出现两处门店名，
     * 反而要额外解释它们为什么可能不同。这不是漏填，需要它时由展示「某店的券」的那条路径补。
     */
    @Override
    @Transactional(readOnly = true)
    public Map<Long, CouponDtos.CouponView> couponsOf(Collection<Long> couponIds) {
        Map<Long, CouponDtos.CouponView> views = new HashMap<>();
        if (couponIds == null || couponIds.isEmpty()) {
            // 空集合不查库：IN () 会被 MySQL 拒绝，也让调用方的空列表变成一次无意义的往返
            return views;
        }
        List<Coupon> coupons = couponMapper.selectBatchIds(couponIds);
        Set<Long> templateIds = coupons.stream().map(Coupon::getTemplateId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, CouponTemplate> templates = templateIds.isEmpty() ? Map.of()
                : templateMapper.selectBatchIds(templateIds).stream()
                        .collect(Collectors.toMap(CouponTemplate::getId, template -> template));
        for (Coupon coupon : coupons) {
            views.put(coupon.getId(),
                    PrivilegeViews.toCouponView(coupon, templates.get(coupon.getTemplateId()), null));
        }
        return views;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public CouponCheck check(long couponId, Long providerId, String serviceCode, BigDecimal orderAmount) {
        Coupon coupon = requireCoupon(couponId);
        Availability availability = availability(coupon, providerId, serviceCode, orderAmount);
        if (!availability.available()) {
            throw new BusinessException(availability.errorCode(), availability.message());
        }
        return new CouponCheck(coupon.getId(), coupon.getCode(), coupon.getFaceValue(),
                coupon.getMinAmount(), coupon.getProviderId());
    }

    /**
     * 「这张券能不能用在这一单」的**唯一实现**：状态、有效期、门槛、门店、服务项范围。
     *
     * <p>下单前的 {@link #check}（80001 那条路径）与 C 端券列表的自动选优都走这里。
     * 两处各写一份的结果是「券包说能用、下单报不能用」——用户看到的是一句解释不通的错，
     * 而这种分叉在改门槛/改范围时最容易出现。
     */
    private Availability availability(Coupon coupon, Long providerId, String serviceCode, BigDecimal orderAmount) {
        LocalDateTime now = AppTime.now();
        if (coupon.getStatus() != null && coupon.getStatus() == Coupon.STATUS_REDEEMED) {
            return new Availability(false, ErrorCode.COUPON_REDEEMED, "该券已核销");
        }
        if (coupon.isExpiredAt(now)) {
            return new Availability(false, ErrorCode.COUPON_UNAVAILABLE, "该券已过期");
        }
        if (coupon.getStatus() != null && coupon.getStatus() == Coupon.STATUS_LOCKED) {
            return new Availability(false, ErrorCode.COUPON_UNAVAILABLE, "该券正在被其它订单使用");
        }
        if (coupon.getMinAmount() != null && orderAmount != null
                && orderAmount.compareTo(coupon.getMinAmount()) < 0) {
            return new Availability(false, ErrorCode.COUPON_UNAVAILABLE,
                    "该券需满 ¥" + coupon.getMinAmount().toPlainString() + " 可用");
        }
        // 服务者贡献的券**只在其本店核销**（ADR-0037 第三节）
        if (coupon.getProviderId() != null && !coupon.getProviderId().equals(providerId)) {
            return new Availability(false, ErrorCode.COUPON_UNAVAILABLE, "该券仅限出券门店使用");
        }
        if (!scopeAllows(coupon, serviceCode)) {
            return new Availability(false, ErrorCode.COUPON_UNAVAILABLE, "该券不适用于这个服务项");
        }
        return AVAILABLE;
    }

    /** 判定结果：能用，或不能用（原因 + 错误码）。 */
    private record Availability(boolean available, ErrorCode errorCode, String message) {
    }

    private static final Availability AVAILABLE = new Availability(true, null, "");

    /**
     * 这张券对这一单可用吗——{@link #check} 的布尔版（自动选优用它筛候选，不抛异常）。
     *
     * <p>调用方持有的必须**是这个用户的**券（本方法只判「能不能用」，不判「是不是他的」）。
     */
    public boolean applies(Coupon coupon, Long providerId, String serviceCode, BigDecimal orderAmount) {
        return availability(coupon, providerId, serviceCode, orderAmount).available();
    }

    /**
     * 从一批券里挑**最优可用券**：先按 {@link #applies} 筛，再按 面额降序 → 到期升序 → id 升序。
     *
     * <p>「先筛条件、后比大小」是刻意的：反过来会挑出一张门槛更高的券，用户点下去才发现不可用。
     * 面额相同时选**先过期**的那张（用户少浪费）；面额与到期都一样时按 id——给一个确定的答案，
     * 不把「数据库碰巧返回的顺序」当业务规则。
     *
     * <p>没有可用券时返回空：调用方（C 端券列表）据此把 {@code recommended} 全置 false，
     * 而不是挑一张「最不差」的券出来。
     */
    public Optional<Long> bestCouponId(List<Coupon> coupons, Long providerId, String serviceCode,
                                       BigDecimal orderAmount) {
        if (coupons == null || coupons.isEmpty()) {
            return Optional.empty();
        }
        return coupons.stream()
                .filter(coupon -> applies(coupon, providerId, serviceCode, orderAmount))
                .min(Comparator
                        // 面额降序（大的先）；面额为 null 的排最后，不然「没有面额」会赢
                        .comparing(Coupon::getFaceValue,
                                Comparator.nullsLast(Comparator.<BigDecimal>reverseOrder()))
                        // 面额相同：先过期的先用（少浪费用户的券）
                        .thenComparing(Coupon::getValidUntil, Comparator.nullsLast(Comparator.naturalOrder()))
                        // 都一样：按 id 升序——确定的答案，不是「碰巧的顺序」
                        .thenComparing(Coupon::getId))
                .map(Coupon::getId);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void lock(long couponId, long orderId, long userId) {
        Coupon coupon = requireCoupon(couponId);
        if (coupon.getStatus() != null && coupon.getStatus() == Coupon.STATUS_LOCKED
                && Long.valueOf(orderId).equals(coupon.getLockedOrderId())) {
            return; // 同一订单重复锁定：幂等（重试不该报错）
        }
        // 用户先在券包页显式占用（持有者 = 占位订单 0）之后再下单：这一单要接管它。
        // 「两个入口对同一张券的效果一样」是契约写的，所以这里必须认这个占位持有者——
        // 否则「先锁券、再下单」会拿到 80001，而用户的动作完全正当。
        if (coupon.getStatus() != null && coupon.getStatus() == Coupon.STATUS_REDEEMED) {
            throw new BusinessException(ErrorCode.COUPON_REDEEMED, "该券已核销");
        }
        // 条件更新：只有「这个用户的、还没被占用的、没过期的」券能被锁定。
        // 是否满足门槛与适用范围由调用方在 check() 里先问过——锁只解决并发占用。
        int affected = couponMapper.update(null, Wrappers.<Coupon>lambdaUpdate()
                .eq(Coupon::getId, couponId)
                .eq(Coupon::getUserId, userId)
                .ge(Coupon::getValidUntil, AppTime.now())
                .and(w -> w.eq(Coupon::getStatus, Coupon.STATUS_UNUSED)
                        .or(inner -> inner.eq(Coupon::getStatus, Coupon.STATUS_LOCKED)
                                .eq(Coupon::getLockedOrderId, STANDALONE_HOLDER)))
                .set(Coupon::getStatus, Coupon.STATUS_LOCKED)
                .set(Coupon::getLockedOrderId, orderId)
                .set(Coupon::getLockedAt, AppTime.now()));
        if (affected == 0) {
            throw new BusinessException(ErrorCode.COUPON_UNAVAILABLE, "该券正在被其它订单使用");
        }
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void release(long couponId, long orderId) {
        // 条件更新：只有「被本订单锁定的那张」会被放回去，别的订单的锁不会被误放
        couponMapper.update(null, Wrappers.<Coupon>lambdaUpdate()
                .eq(Coupon::getId, couponId)
                .eq(Coupon::getStatus, Coupon.STATUS_LOCKED)
                .eq(Coupon::getLockedOrderId, orderId)
                .set(Coupon::getStatus, Coupon.STATUS_UNUSED)
                .set(Coupon::getLockedOrderId, null)
                .set(Coupon::getLockedAt, null));
    }

    /**
     * {@inheritDoc}
     *
     * <p>隔离级别取 READ COMMITTED 是为了**失败后的回读**：条件更新没改到行时，
     * 要读的是「此刻最新已提交的状态」才能区分「刚被别人的核销抢走了」（80002）
     * 与「真的不可用」（80001）。在默认的 REPEATABLE READ 下，这个回读会命在本事务
     * 早先建立的快照上，于是并发双击的败者会拿到 80001——那是错的分类，
     * 而且是实测出来的（{@code CouponQuotaTest.redeemIsAtomic}）。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void redeem(long couponId, Long providerId, long orderId, long operatorId) {
        Coupon coupon = requireCoupon(couponId);
        if (coupon.getStatus() != null && coupon.getStatus() == Coupon.STATUS_REDEEMED) {
            throw new BusinessException(ErrorCode.COUPON_REDEEMED, "该券已核销");
        }
        if (coupon.getProviderId() != null && !coupon.getProviderId().equals(providerId)) {
            throw new BusinessException(ErrorCode.COUPON_UNAVAILABLE, "该券仅限出券门店核销");
        }
        LocalDateTime now = AppTime.now();
        // 原子条件更新：status in (1,2) 且未过期。并发双击只有一个能改到行
        int affected = couponMapper.update(null, Wrappers.<Coupon>lambdaUpdate()
                .eq(Coupon::getId, couponId)
                .in(Coupon::getStatus, Coupon.STATUS_UNUSED, Coupon.STATUS_LOCKED)
                .ge(Coupon::getValidUntil, now)
                .set(Coupon::getStatus, Coupon.STATUS_REDEEMED)
                .set(Coupon::getRedeemedOrderId, orderId)
                .set(Coupon::getRedeemedAt, now)
                .set(Coupon::getRedeemedBy, operatorId));
        if (affected == 0) {
            // 到这一步说明状态/时间已经不满足：要么刚被别人核销，要么已过期
            Coupon latest = requireCoupon(couponId);
            if (latest.getStatus() != null && latest.getStatus() == Coupon.STATUS_REDEEMED) {
                throw new BusinessException(ErrorCode.COUPON_REDEEMED, "该券已核销");
            }
            throw new BusinessException(ErrorCode.COUPON_UNAVAILABLE, "该券不可用（可能已过期）");
        }
    }

    // ---------------------------------------------------------------- 内部

    /** 券码：12 位、去掉易混字符，**不是安全凭证**（防截图靠的是「谁有权核销」而不是码本身）。 */
    private String nextCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_ALPHABET[RANDOM.nextInt(CODE_ALPHABET.length)]);
        }
        return sb.toString();
    }

    private Coupon requireCoupon(long couponId) {
        Coupon coupon = couponMapper.selectById(couponId);
        if (coupon == null) {
            throw BusinessException.notFound("券不存在");
        }
        return coupon;
    }

    /**
     * 适用范围校验：0 不限 / 1 限分类 / 2 限目录项。
     *
     * <p>订单侧手上只有**目录项编码**（{@code HE-004}），而「限分类」的券是按**分类编码**
     * （{@code HOSPITAL}）配的，所以这里要用目录数据把前者翻译成后者
     * （ADR-0037：适用范围与标准目录编码挂钩）。这层翻译**不能**靠「取编码前两位」——
     * 前缀与分类的绑定是目录侧的数据（{@code service_category.item_code_prefix}），
     * 在券这里再推一遍就是第二个真相。
     *
     * <p>{@code serviceCode} 为空表示调用方不校验范围（例如只校验门槛的展示查询）。
     */
    private boolean scopeAllows(Coupon coupon, String serviceCode) {
        int scopeType = coupon.getScopeType() == null ? CouponTemplate.SCOPE_NONE : coupon.getScopeType();
        if (scopeType == CouponTemplate.SCOPE_NONE || serviceCode == null || serviceCode.isBlank()) {
            return true;
        }
        List<String> codes = coupon.getScopeCodes() == null || coupon.getScopeCodes().isBlank()
                ? List.of() : List.of(coupon.getScopeCodes().split(","));
        String code = serviceCode.trim();
        return switch (scopeType) {
            case CouponTemplate.SCOPE_ITEM -> codes.contains(code);
            case CouponTemplate.SCOPE_CATEGORY -> catalog.findItem(code)
                    .map(item -> codes.contains(item.categoryCode()))
                    .orElse(false);
            default -> true;
        };
    }

    private CouponInfo toInfo(Coupon coupon, CouponTemplate template) {
        List<String> scopeCodes = coupon.getScopeCodes() == null || coupon.getScopeCodes().isBlank()
                ? List.of() : List.of(coupon.getScopeCodes().split(","));
        return new CouponInfo(coupon.getId(), coupon.getCode(), coupon.getUserId(), coupon.getTemplateId(),
                template == null ? null : template.getName(), coupon.getFaceValue(), coupon.getMinAmount(),
                coupon.getSource(), coupon.getContributionId(), coupon.getProviderId(),
                coupon.getScopeType(), scopeCodes);
    }
}
