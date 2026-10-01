package com.pethealth.privilege.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pethealth.api.app.InviteCenterView;
import com.pethealth.api.app.InviteCodeView;
import com.pethealth.api.app.InviteLadderProgressView;
import com.pethealth.api.app.PointExchangeView;
import com.pethealth.api.app.PointTaskProgressView;
import com.pethealth.api.app.PointSignInView;
import com.pethealth.api.app.PointsCenterView;
import com.pethealth.api.account.UserQueryApi;
import com.pethealth.api.privilege.CouponDtos;
import com.pethealth.api.privilege.InviteDtos;
import com.pethealth.api.privilege.PointsDtos;
import com.pethealth.api.privilege.RightsDtos;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.error.ErrorCode;
import com.pethealth.common.time.AppTime;
import com.pethealth.common.web.ClientIp;
import com.pethealth.privilege.api.InviteAttributionApi;
import com.pethealth.privilege.api.PointsApi;
import com.pethealth.privilege.api.RightsApi;
import com.pethealth.privilege.domain.Coupon;
import com.pethealth.privilege.domain.CouponTemplate;
import com.pethealth.privilege.domain.InviteCode;
import com.pethealth.privilege.domain.InviteLadderAchievement;
import com.pethealth.privilege.domain.InviteLadderTier;
import com.pethealth.privilege.domain.InviteRelation;
import com.pethealth.privilege.domain.PointBehavior;
import com.pethealth.privilege.domain.PointConfig;
import com.pethealth.privilege.domain.PointExchangeOption;
import com.pethealth.privilege.domain.PointTask;
import com.pethealth.privilege.domain.RightsCode;
import com.pethealth.privilege.mapper.CouponMapper;
import com.pethealth.privilege.mapper.CouponTemplateMapper;
import com.pethealth.privilege.mapper.InviteCodeMapper;
import com.pethealth.privilege.mapper.InviteLadderAchievementMapper;
import com.pethealth.privilege.mapper.InviteLadderTierMapper;
import com.pethealth.privilege.mapper.InviteRelationMapper;
import com.pethealth.privilege.mapper.InviteRiskRecordMapper;
import com.pethealth.privilege.mapper.PointBehaviorMapper;
import com.pethealth.privilege.mapper.PointConfigMapper;
import com.pethealth.privilege.mapper.PointExchangeOptionMapper;
import com.pethealth.privilege.mapper.PointRecordMapper;
import com.pethealth.privilege.mapper.PointTaskMapper;
import com.pethealth.privilege.mapper.RightsCodeMapper;
import com.pethealth.privilege.mapper.UserPointMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * C 端增长链路的四个出口（券 / 积分 / 权益 / 邀请，切片 #110–#113 的 C 端半边）。
 *
 * <p>为什么这些接口在 ph-privilege 而不是别处：数据全是它的表（券 / 积分 / 权益 / 邀请），
 * ADR-0006 禁止别的模块 join 它们；而 {@code /points/exchange} 还是**写**
 * （扣分 + 发券要在同一个事务里），放出去就变成「别的模块改它的数据」。
 * 契约里这四个域也确实是它自己的（`contract/app.yaml` 的 {@code /coupons}、{@code /points}、
 * {@code /rights}、{@code /invites/**}）。
 *
 * <p>四条贯穿这里的分工：
 *
 * <ul>
 *   <li><b>判定只看一处</b>：权益走 {@link RightsApi#evaluate}（ADR-0045），积分扣减与发券走
 *       {@link PointsApi#exchange}（同一事务），本类只做**形状装配**，不重写任何规则；
 *   <li><b>进度是算出来的</b>：任务进度按积分流水聚合（ADR-0046 第七节），邀请阶梯按有效邀请数算
 *       （不按注册数，ADR-0046 第二节）；
 *   <li><b>不产生资金</b>：券只是到店抵扣凭证，积分只能兑券（ADR-0036）；
 *   <li><b>批次取数</b>：一页 20 张券只查一次模板、一次门店名，不在循环里查库。
 * </ul>
 */
@Service
public class AppGrowthConsole {

    private final CouponMapper couponMapper;
    private final CouponTemplateMapper templateMapper;
    private final UserPointMapper pointAccountMapper;
    private final PointRecordMapper pointRecordMapper;
    private final PointBehaviorMapper behaviorMapper;
    private final PointTaskMapper taskMapper;
    private final PointConfigMapper configMapper;
    private final PointExchangeOptionMapper optionMapper;
    private final RightsCodeMapper rightsCodeMapper;
    private final InviteCodeMapper inviteCodeMapper;
    private final InviteRelationMapper relationMapper;
    private final InviteRiskRecordMapper riskMapper;
    private final InviteLadderTierMapper ladderTierMapper;
    private final InviteLadderAchievementMapper ladderAchievementMapper;
    private final PointsApi pointsApi;
    private final RightsApi rightsApi;
    private final InviteAttributionApi inviteApi;
    private final CouponService couponService;
    private final ProviderConsole providerConsole;
    private final UserQueryApi accounts;

    public AppGrowthConsole(CouponMapper couponMapper, CouponTemplateMapper templateMapper,
                            UserPointMapper pointAccountMapper, PointRecordMapper pointRecordMapper,
                            PointBehaviorMapper behaviorMapper, PointTaskMapper taskMapper,
                            PointConfigMapper configMapper, PointExchangeOptionMapper optionMapper,
                            RightsCodeMapper rightsCodeMapper, InviteCodeMapper inviteCodeMapper,
                            InviteRelationMapper relationMapper, InviteRiskRecordMapper riskMapper,
                            InviteLadderTierMapper ladderTierMapper,
                            InviteLadderAchievementMapper ladderAchievementMapper,
                            PointsApi pointsApi, RightsApi rightsApi, InviteAttributionApi inviteApi,
                            CouponService couponService, ProviderConsole providerConsole,
                            UserQueryApi accounts) {
        this.couponMapper = couponMapper;
        this.templateMapper = templateMapper;
        this.pointAccountMapper = pointAccountMapper;
        this.pointRecordMapper = pointRecordMapper;
        this.behaviorMapper = behaviorMapper;
        this.taskMapper = taskMapper;
        this.configMapper = configMapper;
        this.optionMapper = optionMapper;
        this.rightsCodeMapper = rightsCodeMapper;
        this.inviteCodeMapper = inviteCodeMapper;
        this.relationMapper = relationMapper;
        this.riskMapper = riskMapper;
        this.ladderTierMapper = ladderTierMapper;
        this.ladderAchievementMapper = ladderAchievementMapper;
        this.pointsApi = pointsApi;
        this.rightsApi = rightsApi;
        this.inviteApi = inviteApi;
        this.couponService = couponService;
        this.providerConsole = providerConsole;
        this.accounts = accounts;
    }

    // ---------------------------------------------------------------- 券

    /**
     * 我的券（分页 + 状态 / 来源筛选，按发放时间倒序）。
     *
     * <p>券由平台**定向发放**（邀请 / 打卡 / 积分兑换 / 平台补贴 / 月度阶梯），**不做抢券**
     * （ADR-0037 第三节）。{@code providerName} 是**核销门店**：服务者贡献的券只能在其本店核销，
     * 所以用户手里拿到的是「某某店的券」。
     */
    @Transactional(readOnly = true)
    public PageResult<CouponDtos.CouponView> listMyCoupons(long userId, Integer status, Integer source,
                                                           Long providerId, BigDecimal amount, String serviceCode,
                                                           long page, long pageSize) {
        IPage<Coupon> result = couponMapper.selectPage(new Page<>(page, pageSize),
                Wrappers.<Coupon>lambdaQuery()
                        .eq(Coupon::getUserId, userId)
                        .eq(status != null, Coupon::getStatus, status)
                        .eq(source != null, Coupon::getSource, source)
                        .orderByDesc(Coupon::getId));
        List<Coupon> coupons = result.getRecords();
        Map<Long, CouponTemplate> templates = templatesOf(coupons);
        Map<Long, String> providerNames = providerNamesOf(coupons);
        // 「能不能用 / 是不是最优」只在调用方给了门店时算：没有门店就没有「这一单」，
        // 判不了也不假装判过（两个字段留 null，与 false 是两件事）——这是 C 端下单页的默认选券依据。
        // 一次算完：`select` 内部对每张券只判一遍（逐张判两遍会让按分类限定的券多查一倍目录）
        CouponService.CouponSelection selection = providerId == null
                ? null : couponService.select(coupons, providerId, serviceCode, amount);
        List<CouponDtos.CouponView> views = new ArrayList<>(coupons.size());
        for (Coupon coupon : coupons) {
            Boolean applies = selection == null ? null : selection.applies().get(coupon.getId());
            // recommended 与 applies 同进同出：没给门店时两个都是 null（没判），不是 false（判过不能用）
            Boolean recommended = selection == null ? null : coupon.getId().equals(selection.recommendedId());
            views.add(PrivilegeViews.toCouponView(coupon, templates.get(coupon.getTemplateId()),
                    coupon.getProviderId() == null ? null : providerNames.get(coupon.getProviderId()),
                    applies, recommended));
        }
        return PageResult.of(views, result.getCurrent(), result.getSize(), result.getTotal());
    }

    // ---------------------------------------------------------------- 积分

    /**
     * 每日签到（F017 任务清单的第一条）。
     *
     * <p>**幂等靠行为自己的规则，不靠 Idempotency-Key**：这一次的引用拼了业务日期
     * （`point_record.source_ref` 上有唯一键），而 `SIGN_IN` 在 `point_behavior` 里的
     * 每日次数上限本来就是 1——同一天再点一次得到 `awarded=false`，**那是结果不是错误**
     * （客户端双击、重试都安全）。判定全在 {@link PointsApi#award} 里，
     * 本方法只把结果翻译成契约的形状：与这个类的其余出口一样，**不重写任何规则**。
     *
     * <p>分值由运营在后台配（种子里 1 分；配成 0 就是「记行为不发分」），所以文案要跟着分值走——
     * 写死「+1 积分」的话，运营一改分值这句话就成了假话。
     */
    @Transactional
    public PointSignInView signIn(long userId) {
        PointsApi.AwardResult result = pointsApi.award(new PointsApi.AwardCommand(
                userId, PointBehavior.SIGN_IN, "sign-in:" + AppTime.today(), "每日签到"));
        String notice;
        if (!result.awarded()) {
            notice = "今天已经签过了";
        } else if (result.points() > 0) {
            notice = "签到成功，+" + result.points() + " 积分";
        } else {
            notice = "签到成功";
        }
        return new PointSignInView(result.awarded(), result.points(), result.balanceAfter(), notice);
    }

    /**
     * 积分中心：账户 + 任务进度 + 行为分值表 + 兑换档位。
     *
     * <p>任务进度按**行为流水聚合**：每日任务看今天的流水，每周任务看本周（周一起）的流水。
     * 任务自己**不发分**，{@code points} 只是把该行为的分值取出来展示（ADR-0038 第四节）。
     *
     * <p>**停用项不下发**（任务与行为两处同一口径）：停用的任务不进清单——否则用户看到一条
     * 永远做不完的任务（`DAILY_SHARE` 就是这样：桌面 Web 上分享没有服务端可观测的事件）。
     * 行为那边同理：停用的行为不再出现在「行为分值」表里（它已经不会发生了，列着只会误导，
     * 比如给一条「0 分 · 每日 1 次」的分享）。**但查名字用的那张表不过滤**：
     * 任务引用的行为可能已停用，那条任务仍要能显示「打卡」这个名字而不是一片空白。
     */
    @Transactional(readOnly = true)
    public PointsCenterView pointsCenter(long userId) {
        Integer balance = Optional.ofNullable(pointAccountMapper.selectOne(
                        Wrappers.<com.pethealth.privilege.domain.UserPoint>lambdaQuery()
                                .eq(com.pethealth.privilege.domain.UserPoint::getUserId, userId)))
                .map(com.pethealth.privilege.domain.UserPoint::getBalance).orElse(0);
        int todayEarned = pointsApi.todayEarned(userId);
        int dailyLimit = Optional.ofNullable(configMapper.selectById(1L))
                .map(PointConfig::getDailyEarnLimit).orElse(20);

        LocalDate today = AppTime.today();
        LocalDate weekStart = today.with(DayOfWeek.MONDAY);
        Map<String, Integer> todayCounts = countByBehavior(userId, today, today);
        Map<String, Integer> weekCounts = countByBehavior(userId, weekStart, today);

        List<PointTask> tasks = taskMapper.selectList(Wrappers.<PointTask>lambdaQuery()
                .eq(PointTask::getStatus, PointTask.STATUS_ENABLED)
                .orderByAsc(PointTask::getPeriod)
                .orderByAsc(PointTask::getSortOrder)
                .orderByAsc(PointTask::getId));
        Map<String, PointBehavior> behaviors = new LinkedHashMap<>();
        List<PointBehavior> enabledBehaviors = new ArrayList<>();
        for (PointBehavior behavior : behaviorMapper.selectList(Wrappers.<PointBehavior>lambdaQuery()
                .orderByAsc(PointBehavior::getSortOrder)
                .orderByAsc(PointBehavior::getId))) {
            behaviors.put(behavior.getCode(), behavior);
            if (behavior.isEnabled()) {
                enabledBehaviors.add(behavior);
            }
        }

        List<PointTaskProgressView> taskViews = new ArrayList<>(tasks.size());
        for (PointTask task : tasks) {
            PointBehavior behavior = behaviors.get(task.getBehaviorCode());
            int current = (task.getPeriod() == PointTask.PERIOD_WEEKLY
                    ? weekCounts : todayCounts).getOrDefault(task.getBehaviorCode(), 0);
            int target = task.getTargetCount() == null ? 1 : task.getTargetCount();
            taskViews.add(new PointTaskProgressView(
                    task.getId(),
                    task.getCode(),
                    task.getName(),
                    task.getPeriod(),
                    task.getBehaviorCode(),
                    behavior == null ? null : behavior.getName(),
                    target,
                    current,
                    current >= target,
                    behavior == null || behavior.getPoints() == null ? 0 : behavior.getPoints()));
        }

        List<PointsDtos.PointExchangeOptionView> options = new ArrayList<>();
        for (PointExchangeOption option : optionMapper.selectList(Wrappers.<PointExchangeOption>lambdaQuery()
                .eq(PointExchangeOption::getStatus, PointExchangeOption.STATUS_ENABLED)
                .orderByAsc(PointExchangeOption::getSortOrder)
                .orderByAsc(PointExchangeOption::getId))) {
            options.add(PrivilegeViews.toExchangeOptionView(option, templateMapper.selectById(option.getCouponTemplateId())));
        }
        return new PointsCenterView(balance, todayEarned, dailyLimit, taskViews,
                enabledBehaviors.stream().map(PrivilegeViews::toBehaviorView).toList(), options);
    }

    /**
     * 用积分兑换券（**写**：扣分 + 发平台补贴券，在同一个事务里）。
     *
     * <p>规则一行都不在这里：扣减、余额不足 40900、档位不存在 40400、发券与幂等全在
     * {@link PointsApi#exchange}（ADR-0046 第六节）。本类只把结果拼成契约的形状。
     */
    @Transactional
    public PointExchangeView exchange(long userId, long optionId) {
        PointsApi.ExchangeResult result = pointsApi.exchange(userId, optionId);
        Coupon coupon = couponMapper.selectById(result.couponId());
        CouponTemplate template = coupon == null ? null : templateMapper.selectById(coupon.getTemplateId());
        String providerName = coupon == null || coupon.getProviderId() == null ? null
                : providerNamesOf(List.of(coupon)).get(coupon.getProviderId());
        return new PointExchangeView(result.pointsCost(), result.balanceAfter(),
                coupon == null ? null : PrivilegeViews.toCouponView(coupon, template, providerName));
    }

    // ---------------------------------------------------------------- 权益

    /**
     * 我的权益：每个码「是否生效 + 来源 + 到期」。
     *
     * <p>判定**实时**（不缓存，ADR-0045 第一节），来源优先级 订阅 &gt; 邀请 &gt; 打卡，
     * **不按到期时间比较**。{@code effective=false} 的码也列出来：界面要能显示
     * 「你有这项权益、但当前不生效」，而不是让用户看到一个空的权益页。
     */
    @Transactional(readOnly = true)
    public RightsDtos.RightsEvaluationView rights(long userId) {
        Map<String, RightsApi.RightsState> states = rightsApi.evaluate(userId);
        List<RightsCode> codes = rightsCodeMapper.selectList(Wrappers.<RightsCode>lambdaQuery()
                .eq(RightsCode::getStatus, RightsCode.STATUS_ENABLED)
                .orderByAsc(RightsCode::getSortOrder)
                .orderByAsc(RightsCode::getId));
        List<RightsDtos.RightsItemView> items = new ArrayList<>(codes.size());
        for (RightsCode code : codes) {
            RightsApi.RightsState state = states.get(code.getCode());
            items.add(PrivilegeViews.toRightsItemView(code,
                    state != null && state.effective(),
                    state == null ? null : state.source(),
                    state == null ? null : state.expireAt()));
        }
        return new RightsDtos.RightsEvaluationView(userId, items);
    }

    // ---------------------------------------------------------------- 邀请

    /**
     * 我的邀请码与邀请进度。
     *
     * <p>{@code registeredCount} 只数**归因成功**的关系（被反作弊拦下的进 {@code invalidCount}，
     * 它们从来没有成为一条关系）；{@code effectiveCount} 是阶梯与积分唯一的计分依据（ADR-0046 第二节）。
     */
    @Transactional(readOnly = true)
    public InviteCenterView inviteCenter(long userId) {
        List<InviteCode> codes = inviteCodeMapper.selectList(Wrappers.<InviteCode>lambdaQuery()
                .eq(InviteCode::getUserId, userId)
                .orderByDesc(InviteCode::getId));
        long registered = relationMapper.selectCount(Wrappers.<InviteRelation>lambdaQuery()
                .eq(InviteRelation::getInviterUserId, userId));
        long pending = relationMapper.selectCount(Wrappers.<InviteRelation>lambdaQuery()
                .eq(InviteRelation::getInviterUserId, userId)
                .eq(InviteRelation::getStatus, InviteRelation.STATUS_PENDING));
        long effective = relationMapper.selectCount(Wrappers.<InviteRelation>lambdaQuery()
                .eq(InviteRelation::getInviterUserId, userId)
                .eq(InviteRelation::getStatus, InviteRelation.STATUS_EFFECTIVE));
        long invalidRelations = relationMapper.selectCount(Wrappers.<InviteRelation>lambdaQuery()
                .eq(InviteRelation::getInviterUserId, userId)
                .eq(InviteRelation::getStatus, InviteRelation.STATUS_INVALID));
        long blocked = riskMapper.selectCount(Wrappers.<com.pethealth.privilege.domain.InviteRiskRecord>lambdaQuery()
                .eq(com.pethealth.privilege.domain.InviteRiskRecord::getInviterUserId, userId));

        Map<Integer, InviteLadderAchievement> achievements = new HashMap<>();
        for (InviteLadderAchievement achievement : ladderAchievementMapper.selectList(
                Wrappers.<InviteLadderAchievement>lambdaQuery()
                        .eq(InviteLadderAchievement::getUserId, userId))) {
            achievements.put(achievement.getThreshold(), achievement);
        }
        // 与发奖侧共用同一处读法：停用的档位不出现在进度里——否则用户会看到一个
        // 永远不会兑现的目标（见 InviteLadder 的类注释）
        List<InviteLadderTier> tiers = InviteLadder.enabledTiers(ladderTierMapper);
        List<InviteLadderProgressView> ladder = new ArrayList<>(tiers.size());
        Integer nextThreshold = null;
        Integer nextRemaining = null;
        for (InviteLadderTier tier : tiers) {
            InviteLadderAchievement achievement = achievements.get(tier.getThreshold());
            boolean achieved = achievement != null;
            ladder.add(new InviteLadderProgressView(tier.getThreshold(), achieved,
                    achievement == null ? null : achievement.getAchievedAt(),
                    rewardDesc(tier)));
            if (!achieved && nextThreshold == null) {
                nextThreshold = tier.getThreshold();
                nextRemaining = (int) Math.max(tier.getThreshold() - effective, 0);
            }
        }
        return new InviteCenterView(
                codes.stream().map(AppGrowthConsole::toInviteCodeView).toList(),
                (int) registered, (int) pending, (int) effective, (int) (invalidRelations + blocked),
                ladder, nextThreshold, nextRemaining);
    }

    /**
     * 生成（或取回）我的邀请码。
     *
     * <p>**一人一码**：已经有码就直接返回那一条，所以 {@code channel} 与 {@code deviceId} 记的都是
     * **首次生成时**的值（ADR-0049 §八），后来的调用不会改写它们。生成不删旧码——
     * 旧码已经分享出去了，删掉等于让那些链接失效。
     *
     * <p><b>三层反作弊的比较基准都在这里落库</b>：IP 与号段由服务端自己取（客户端声明的地址
     * 不是证据，见 {@code ClientIp} 与 ADR-0028），设备号由客户端上报（桌面 Web 没有更硬的来源，
     * 所以它的作用是「两台设备是不是同一台」，不是「证明是谁」）。三者都不传时，
     * 对应的那层判据不成立——**留空是有效状态**，不是「忘了接线」。
     * 原先这里传的是三个 null，两层判据因此在真实使用中永远不会命中（2026-09-30 验收）。
     */
    @Transactional
    public InviteCodeView createInviteCode(long userId, Integer channel, String deviceId) {
        int entry = channel == null ? InviteRelation.CHANNEL_LINK : channel;
        // ensureInviteCode 的语义就是「一人一码，重复调用返回同一个」（ph-privilege 的 InviteService）
        String code = inviteApi.ensureInviteCode(userId, entry,
                deviceId,
                ClientIp.current(),
                accounts.phoneSegmentOf(userId).orElse(null));
        InviteCode row = inviteCodeMapper.selectOne(Wrappers.<InviteCode>lambdaQuery()
                .eq(InviteCode::getUserId, userId)
                .eq(InviteCode::getCode, code)
                .orderByDesc(InviteCode::getId)
                .last("LIMIT 1"));
        if (row == null) {
            throw BusinessException.conflict("邀请码生成失败，请重试");
        }
        return toInviteCodeView(row);
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 邀请码 → 契约视图。
     *
     * <p>{@code status} 恒回 1（启用）：契约里有这个字段，而 {@code invite_code} 表**没有状态列**
     * ——「停用某条码」的入口还不存在，所以「所有已生成的码都是启用中」是当下的事实，
     * 不是把不知道的事填成 1。真要能停用，需要一列 + 一个入口（见 ADR-0048 的待澄清）。
     */
    private static InviteCodeView toInviteCodeView(InviteCode code) {
        return new InviteCodeView(code.getId(), code.getCode(), code.getChannel(),
                "/register?invite=" + code.getCode(), 1, code.getCreatedAt());
    }

    /** 阶梯奖励说明：**没配奖励时为空**（达成照记，只是不发东西，ADR-0046 第五节）。 */
    private String rewardDesc(InviteLadderTier tier) {
        if (tier.getRewardType() == null) {
            return null;
        }
        int count = tier.getRewardCount() == null ? 1 : tier.getRewardCount();
        if (tier.getRewardType() == InviteLadderTier.REWARD_COUPON && tier.getCouponTemplateId() != null) {
            CouponTemplate template = templateMapper.selectById(tier.getCouponTemplateId());
            return template == null ? null : template.getName() + " × " + count;
        }
        if (tier.getRewardType() == InviteLadderTier.REWARD_RIGHTS && tier.getRightsCode() != null) {
            RightsCode code = rightsCodeMapper.selectOne(Wrappers.<RightsCode>lambdaQuery()
                    .eq(RightsCode::getCode, tier.getRightsCode()));
            return code == null ? null : code.getName() + " × " + count;
        }
        return null;
    }

    /** 一批券的模板（一次取回，不在循环里查库）。 */
    private Map<Long, CouponTemplate> templatesOf(List<Coupon> coupons) {
        Map<Long, CouponTemplate> templates = new HashMap<>();
        List<Long> ids = coupons.stream().map(Coupon::getTemplateId).distinct().toList();
        if (ids.isEmpty()) {
            return templates;
        }
        for (CouponTemplate template : templateMapper.selectBatchIds(ids)) {
            templates.put(template.getId(), template);
        }
        return templates;
    }

    private Map<Long, String> providerNamesOf(List<Coupon> coupons) {
        List<Long> ids = coupons.stream().map(Coupon::getProviderId).filter(java.util.Objects::nonNull)
                .distinct().toList();
        return providerConsole.namesOf(ids);
    }

    private Map<String, Integer> countByBehavior(long userId, LocalDate from, LocalDate to) {
        Map<String, Integer> counts = new HashMap<>();
        for (PointRecordMapper.BehaviorCount row : pointRecordMapper.countByBehavior(userId, from, to)) {
            counts.put(row.behaviorCode(), row.total());
        }
        return counts;
    }

    // ---------------------------------------------------------------- 券包页的显式占用（切片 #110 的 C 端入口）

    /**
     * 锁定券（**下单前的显式占用**，券包页的「用这张券」）。
     *
     * <p>与下单链路的锁是**同一件事的两条入口**：这里由用户主动占用（持有者是占位订单 0，
     * 见 {@code CouponService.STANDALONE_HOLDER}），下单时会换成真实订单 id，
     * 取消订单时释放的是真实订单持有的那一张——所以这条路不会把订单的锁误放掉。
     *
     * <p>幂等：重复锁定同一张券返回同样的结果（它已被当前用户占用，不是错误）。
     * 契约的 40900 / 80001 / 80002 三档：
     * 已被**别的订单**占用或已过期 → 80001；已核销 → 80002；不属于我 → 40400。
     */
    @Transactional
    public CouponDtos.CouponView lockCoupon(long userId, long couponId) {
        Coupon coupon = requireMyCoupon(userId, couponId);
        if (coupon.getStatus() != null && coupon.getStatus() == Coupon.STATUS_REDEEMED) {
            throw new BusinessException(ErrorCode.COUPON_REDEEMED, "该券已核销");
        }
        if (isHeldByOrder(coupon)) {
            throw new BusinessException(ErrorCode.COUPON_UNAVAILABLE, "该券正在被其它订单使用");
        }
        if (coupon.getStatus() != null && coupon.getStatus() == Coupon.STATUS_LOCKED
                && isStandalone(coupon)) {
            return viewOf(couponId); // 已被我自己占用：幂等返回当前状态
        }
        int affected = couponMapper.update(null, Wrappers.<Coupon>lambdaUpdate()
                .eq(Coupon::getId, couponId)
                .eq(Coupon::getUserId, userId)
                .eq(Coupon::getStatus, Coupon.STATUS_UNUSED)
                .ge(Coupon::getValidUntil, AppTime.now())
                .set(Coupon::getStatus, Coupon.STATUS_LOCKED)
                .set(Coupon::getLockedOrderId, CouponService.STANDALONE_HOLDER)
                .set(Coupon::getLockedAt, AppTime.now()));
        if (affected == 0) {
            // 状态在读到写之间变了：要么刚被别人占用，要么已过期（80001 的两种成因）
            throw new BusinessException(ErrorCode.COUPON_UNAVAILABLE, "该券不可用（可能已被占用或已过期）");
        }
        return viewOf(couponId);
    }

    /**
     * 释放券（取消订单时 / 券包页的「释放」按钮）。
     *
     * <p>只释放**锁定的、未核销的**券：
     *
     * <ul>
     *   <li>已核销 → 80002（券已核销，不可释放）；
     *   <li>被某个**未结束的订单**持有 → **40900**：先取消那一单，取消动作本身会释放它
     *       （不让它被单独释放，是因为那样订单会握着一张已经回到池子里的券——同一个面额被抵扣两次）；
     *   <li>未被锁定（本来就待使用，或已过期）→ 幂等成功，返回当前状态。
     * </ul>
     */
    @Transactional
    public CouponDtos.CouponView releaseCoupon(long userId, long couponId) {
        Coupon coupon = requireMyCoupon(userId, couponId);
        if (coupon.getStatus() != null && coupon.getStatus() == Coupon.STATUS_REDEEMED) {
            throw new BusinessException(ErrorCode.COUPON_REDEEMED, "该券已核销，不能释放");
        }
        if (isHeldByOrder(coupon)) {
            throw BusinessException.conflict("该券正被一个未结束的订单占用，请先取消那一单");
        }
        if (isStandalone(coupon)) {
            couponMapper.update(null, Wrappers.<Coupon>lambdaUpdate()
                    .eq(Coupon::getId, couponId)
                    .eq(Coupon::getUserId, userId)
                    .eq(Coupon::getLockedOrderId, CouponService.STANDALONE_HOLDER)
                    .set(Coupon::getStatus, Coupon.STATUS_UNUSED)
                    .set(Coupon::getLockedOrderId, null)
                    .set(Coupon::getLockedAt, null));
        }
        return viewOf(couponId); // 未锁定（待使用 / 已过期）也走这里：幂等成功
    }

    /** 券是不是被当前用户**显式占用**（占位持有者 0）：是的话 release 才动它。 */
    private static boolean isStandalone(Coupon coupon) {
        return coupon.getStatus() != null && coupon.getStatus() == Coupon.STATUS_LOCKED
                && coupon.getLockedOrderId() != null
                && coupon.getLockedOrderId() == CouponService.STANDALONE_HOLDER;
    }

    /** 券被某个**真实订单**持有（未结束的订单占着它）。 */
    private static boolean isHeldByOrder(Coupon coupon) {
        return coupon.getStatus() != null && coupon.getStatus() == Coupon.STATUS_LOCKED
                && coupon.getLockedOrderId() != null
                && coupon.getLockedOrderId() != CouponService.STANDALONE_HOLDER;
    }

    /** 我的券；不是我的按不存在处理（40400，免得用 id 探测别人手里有什么券）。 */
    private Coupon requireMyCoupon(long userId, long couponId) {
        Coupon coupon = couponMapper.selectById(couponId);
        if (coupon == null || coupon.getUserId() == null || coupon.getUserId() != userId) {
            throw BusinessException.notFound("券不存在");
        }
        return coupon;
    }

    /** 按 id 取券视图（锁 / 释放后回给前端的那一张）。 */
    private CouponDtos.CouponView viewOf(long couponId) {
        Coupon coupon = couponMapper.selectById(couponId);
        CouponTemplate template = coupon == null ? null : templateMapper.selectById(coupon.getTemplateId());
        String providerName = coupon == null || coupon.getProviderId() == null ? null
                : providerNamesOf(List.of(coupon)).get(coupon.getProviderId());
        return PrivilegeViews.toCouponView(coupon, template, providerName);
    }
}
