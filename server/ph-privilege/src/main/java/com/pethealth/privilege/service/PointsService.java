package com.pethealth.privilege.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.error.ErrorCode;
import com.pethealth.common.time.AppTime;
import com.pethealth.privilege.api.CouponApi;
import com.pethealth.privilege.api.PointsApi;
import com.pethealth.privilege.domain.Coupon;
import com.pethealth.privilege.domain.CouponTemplate;
import com.pethealth.privilege.domain.PointBehavior;
import com.pethealth.privilege.domain.PointConfig;
import com.pethealth.privilege.domain.PointExchangeOption;
import com.pethealth.privilege.domain.PointRecord;
import com.pethealth.privilege.domain.UserPoint;
import com.pethealth.privilege.mapper.CouponTemplateMapper;
import com.pethealth.privilege.mapper.PointBehaviorMapper;
import com.pethealth.privilege.mapper.PointConfigMapper;
import com.pethealth.privilege.mapper.PointExchangeOptionMapper;
import com.pethealth.privilege.mapper.PointRecordMapper;
import com.pethealth.privilege.mapper.UserPointMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 积分：行为发分与兑换（切片 #113），决策见 ADR-0038 第四节与 ADR-0046。
 *
 * <p>六条判定按顺序，每一层都可能直接返回「没发成」（都不是异常——发分失败不该让打卡失败）：
 *
 * <pre>
 *   行为码存在且启用 → 同一引用没发过 → 一次性项没发过 → 每日次数 → 每月次数 → 每日上限
 * </pre>
 *
 * <p><b>每日上限（默认 20 分）只作用于占上限的行为</b>：邀请（20 分）与完善档案（10 分）
 * 带 {@code counts_toward_daily_cap = 0}，不占这个额度——否则一次邀请就把当天的日上限吃光，
 * 之后用户做什么都不再得分（ADR-0038 第四节的原话）。
 *
 * <p><b>并发安全靠两处</b>：账户余额用 {@code SELECT ... FOR UPDATE} 锁行后读改写
 * （同一用户并发发分不会丢更新）；扣分用 {@code WHERE balance >= ?} 的原子条件更新
 * （先查后扣在并发下会把余额扣成负数）。
 *
 * <p>积分与券是**两套账**：兑换在一个事务里同时扣分与发券，要么都成、要么都不成。
 */
@Service
public class PointsService implements PointsApi {

    private static final Logger log = LoggerFactory.getLogger(PointsService.class);

    /** 账户不存在时的默认每日上限（与 V27 的种子值一致，兜底用）。 */
    private static final int DEFAULT_DAILY_LIMIT = 20;

    /**
     * 兑换流水的行为码。
     *
     * <p>它**不是**一个可发分的行为（{@code point_behavior} 里没有这一行），
     * 只是流水上的标记：兑换是消耗，不需要分值、频次与日上限那套判据。
     * 运营在流水里看到它时，对应的 `change` 一定是负的。
     */
    static final String EXCHANGE_BEHAVIOR = "POINTS_EXCHANGE";

    private final UserPointMapper accountMapper;
    private final PointRecordMapper recordMapper;
    private final PointBehaviorMapper behaviorMapper;
    private final PointConfigMapper configMapper;
    private final PointExchangeOptionMapper optionMapper;
    private final CouponTemplateMapper templateMapper;
    private final CouponApi couponApi;

    public PointsService(UserPointMapper accountMapper, PointRecordMapper recordMapper,
                         PointBehaviorMapper behaviorMapper, PointConfigMapper configMapper,
                         PointExchangeOptionMapper optionMapper, CouponTemplateMapper templateMapper,
                         CouponApi couponApi) {
        this.accountMapper = accountMapper;
        this.recordMapper = recordMapper;
        this.behaviorMapper = behaviorMapper;
        this.configMapper = configMapper;
        this.optionMapper = optionMapper;
        this.templateMapper = templateMapper;
        this.couponApi = couponApi;
    }

    // ---------------------------------------------------------------- 发分

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AwardResult award(AwardCommand command) {
        PointBehavior behavior = behaviorMapper.selectOne(Wrappers.<PointBehavior>lambdaQuery()
                .eq(PointBehavior::getCode, command.behaviorCode()));
        // 锁账户行**再**判定：并发发分时，「今日已得多少分」「这个行为今天第几次」这些判据
        // 必须在锁内读，否则两个请求会各自读到同一份旧账、一起发分（每日上限被绕过）。
        //
        // 隔离级别取 READ COMMITTED 的理由与券的发放相同（见 CouponService.issue）：
        // MySQL 默认 REPEATABLE READ 下事务第一次一致性读就定快照，等锁之后读到的还是旧版本。
        UserPoint account = lockOrCreate(command.userId());
        int balance = account.getBalance();
        if (behavior == null) {
            log.warn("未知的行为码，未发分：{}", command.behaviorCode());
            return new AwardResult(false, 0, balance, "BEHAVIOR_UNKNOWN");
        }
        if (!behavior.isEnabled()) {
            return new AwardResult(false, 0, balance, "BEHAVIOR_DISABLED");
        }
        LocalDate businessDate = AppTime.today();
        String sourceRef = command.sourceRef() == null || command.sourceRef().isBlank()
                ? null : command.sourceRef().trim();

        String reason = firstBlockingReason(command.userId(), behavior, sourceRef, businessDate);
        if (reason != null) {
            return new AwardResult(false, 0, balance, reason);
        }

        int points = behavior.getPoints() == null ? 0 : behavior.getPoints();
        int after = account.getBalance() + points;
        account.setBalance(after);
        account.setTotalEarned(account.getTotalEarned() + points);
        accountMapper.updateById(account);

        PointRecord record = new PointRecord();
        record.setUserId(command.userId());
        record.setBehaviorCode(behavior.getCode());
        record.setChangeAmount(points);
        record.setBalanceAfter(after);
        record.setCountsTowardDailyCap(behavior.countsTowardDailyCap() ? 1 : 0);
        record.setBusinessDate(businessDate);
        record.setSourceRef(sourceRef);
        record.setRemark(command.remark() == null || command.remark().isBlank() ? null : command.remark().trim());
        try {
            recordMapper.insert(record);
        } catch (DuplicateKeyException e) {
            // 并发下的同一引用：唯一键兜底。**把余额改回去**——分已经加过了，这次不加
            account.setBalance(account.getBalance() - points);
            account.setTotalEarned(account.getTotalEarned() - points);
            accountMapper.updateById(account);
            return new AwardResult(false, 0, account.getBalance(), "DUPLICATE");
        }
        return new AwardResult(true, points, after, null);
    }

    /**
     * 六层判定的前五层（第六层每日上限在调用方一起看，因为它要用到本次分值）。
     *
     * @return 阻断原因；{@code null} 表示可以发
     */
    private String firstBlockingReason(long userId, PointBehavior behavior, String sourceRef,
                                       LocalDate businessDate) {
        if (sourceRef != null && recordMapper.countBySourceRef(userId, behavior.getCode(), sourceRef) > 0) {
            return "DUPLICATE";
        }
        if (behavior.onceOnly() && recordMapper.countAll(userId, behavior.getCode()) > 0) {
            return "ONCE_ONLY";
        }
        if (behavior.getDailyCountLimit() != null && behavior.getDailyCountLimit() > 0
                && recordMapper.countOnDay(userId, behavior.getCode(), businessDate)
                >= behavior.getDailyCountLimit()) {
            return "DAILY_COUNT_LIMIT";
        }
        if (behavior.getMonthlyCountLimit() != null && behavior.getMonthlyCountLimit() > 0) {
            LocalDate first = businessDate.withDayOfMonth(1);
            LocalDate last = first.plusMonths(1).minusDays(1);
            if (recordMapper.countBetween(userId, behavior.getCode(), first, last)
                    >= behavior.getMonthlyCountLimit()) {
                return "MONTHLY_LIMIT";
            }
        }
        int points = behavior.getPoints() == null ? 0 : behavior.getPoints();
        if (behavior.countsTowardDailyCap() && points > 0) {
            int todayPoints = recordMapper.sumDailyCapPoints(userId, businessDate);
            if (todayPoints + points > dailyLimit()) {
                return "DAILY_LIMIT";
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 余额与兑换

    @Override
    @Transactional(readOnly = true)
    public int balanceOf(long userId) {
        UserPoint account = accountMapper.selectOne(Wrappers.<UserPoint>lambdaQuery()
                .eq(UserPoint::getUserId, userId));
        return account == null || account.getBalance() == null ? 0 : account.getBalance();
    }

    @Override
    @Transactional(readOnly = true)
    public int todayEarned(long userId) {
        return recordMapper.sumDailyCapPoints(userId, AppTime.today());
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ExchangeResult exchange(long userId, long optionId) {
        PointExchangeOption option = optionMapper.selectById(optionId);
        if (option == null || !option.isEnabled()) {
            throw BusinessException.notFound("兑换档位不存在或已停用");
        }
        CouponTemplate template = templateMapper.selectById(option.getCouponTemplateId());
        if (template == null || !template.isEnabled()) {
            throw BusinessException.notFound("兑换的券已下线");
        }
        if (!template.isPlatformSubsidy()) {
            // 理论上建不出来（服务层在档位创建时校验过），但这里是最后一道：
            // 一旦真出现，说明有路径绕过了校验，宁可发不出去也不能把成本转嫁给服务者
            throw BusinessException.conflict("该档位配置的不是平台补贴券，暂不可兑换");
        }

        LocalDateTime now = AppTime.now();
        int cost = option.getPointsCost();
        if (accountMapper.spend(userId, cost, now) == 0) {
            int balance = balanceOf(userId);
            throw BusinessException.conflict("积分不足：需要 " + cost + " 分，当前 " + balance + " 分");
        }
        int balanceAfter = balanceOf(userId);

        // 兑换的幂等靠调用方的 Idempotency-Key（ADR-0028）：一次兑换没有天然的业务引用，
        // 所以 source_ref 用「用户 + 档位 + 毫秒」拼一个不会撞的引用，避免把它当成幂等键误用
        String sourceRef = "points-exchange:" + userId + ":" + optionId + ":" + System.currentTimeMillis();
        CouponApi.CouponInfo coupon = couponApi.issue(new CouponApi.IssueCommand(userId,
                template.getId(), Coupon.SOURCE_POINTS_EXCHANGE, sourceRef, null,
                "积分兑换：" + option.getName()));

        PointRecord record = new PointRecord();
        record.setUserId(userId);
        record.setBehaviorCode(EXCHANGE_BEHAVIOR);
        record.setChangeAmount(-cost);
        record.setBalanceAfter(balanceAfter);
        record.setCountsTowardDailyCap(0);
        record.setBusinessDate(AppTime.today());
        record.setSourceRef(sourceRef);
        record.setRemark("兑换：" + option.getName());
        recordMapper.insert(record);

        return new ExchangeResult(coupon.id(), coupon.code(), cost, balanceAfter);
    }

    // ---------------------------------------------------------------- 内部

    /** 每日获取上限（可调项，进库 + 运营后台；缺配置时按 20 兜底）。 */
    int dailyLimit() {
        PointConfig config = configMapper.selectById(PointConfig.SINGLETON_ID);
        return config == null || config.getDailyEarnLimit() == null
                ? DEFAULT_DAILY_LIMIT : config.getDailyEarnLimit();
    }

    /**
     * 锁住账户行；没有账户就建一个空账户再锁。
     *
     * <p>并发首次发分时可能两个线程同时走到「建账户」：唯一键 {@code uk_user} 让其中一个失败，
     * 失败方回读已有的那一行——这正是「先查再插」必须配唯一键的原因。
     */
    private UserPoint lockOrCreate(long userId) {
        UserPoint account = accountMapper.selectForUpdate(userId);
        if (account != null) {
            return account;
        }
        UserPoint created = new UserPoint();
        created.setUserId(userId);
        created.setBalance(0);
        created.setTotalEarned(0);
        created.setTotalSpent(0);
        try {
            accountMapper.insert(created);
        } catch (DuplicateKeyException e) {
            log.debug("并发创建积分账户，回读已有账户：userId={}", userId);
        }
        UserPoint locked = accountMapper.selectForUpdate(userId);
        if (locked == null) {
            throw new BusinessException(ErrorCode.SERVER_ERROR, "积分账户创建失败，请重试");
        }
        return locked;
    }
}
