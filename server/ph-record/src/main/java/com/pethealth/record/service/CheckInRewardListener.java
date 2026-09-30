package com.pethealth.record.service;

import com.pethealth.api.app.CheckInStreak;
import com.pethealth.common.time.AppTime;
import com.pethealth.privilege.api.CouponApi;
import com.pethealth.record.domain.CheckInRewardRule;
import com.pethealth.record.event.CheckInSubmittedEvent;
import com.pethealth.record.mapper.CheckInRewardRuleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDate;
import java.util.List;

/**
 * 打卡 → 得券：接线点（交付文档 F017 的「打卡得券」，此前只发积分）。
 *
 * <p>与 {@link CheckInPointsListener} 是同一件事的两条奖励路径（积分/券），三层口径完全对齐：
 *
 * <ol>
 *   <li><b>AFTER_COMMIT 而不是打卡事务里的一次调用</b>：发券失败不能把打卡一起回滚
 *       （用户填的记录不能白填），券可以补、打卡不能丢。且异常必须在这里接住——
 *       AFTER_COMMIT 的回调发生在提交之后，异常会一路冒到请求线程，让一次成功的打卡变 500；
 *   <li><b>连续天数复用 {@link CheckInService#streak}</b>，不另写一套：
 *       「连续 7 天」的判定与「我的连续天数」页面上显示的数字必须是同一个口径，
 *       两份实现迟早在跨零点、补录、断签这些边上分叉；
 *   <li><b>幂等靠引用 + 唯一键</b>（{@code coupon} 的 {@code (source, source_ref)} 唯一），
 *       而不是靠调用方小心。
 * </ol>
 *
 * <p><b>幂等引用是「人 + 档位 + 这一轮连续的起始日」</b>
 * （{@code checkin-reward:{userId}:{streakDays}:{runStartDate}}），三个成分各有原因：
 *
 * <ul>
 *   <li><b>人</b>（不是宠物）：奖励按用户算，与打卡发分同口径（{@code CHECK_IN} 换一只宠物
 *       也不重复发）。所以同一用户两只宠物同时攒到同一档位时只发一张——这不是漏发，是刻意的；
 *   <li><b>档位</b>：多档位时各档各发一张；
 *   <li><b>这一轮连续的起始日</b>（而不是「提交的那一天」）：它才是**「这一次达成」的身份**。
 *       同一轮连续里第 7 天、第 8 天算出的起始日是同一天 → 同一轮只发一张；
 *       而「撤销一项把连续打断、再重新攒满 7 天」是一轮新的连续，起始日变了 → 可以再拿一张
 *       （这正是「连续中断后从 1 重算」的语义）。用提交日的话，同一天怎么重跑都不会多发，
 *       但换个日子 undo + 重打就能再薅一张——那才是漏洞。
 * </ul>
 *
 * <p><b>档位判定是「达到即发」</b>（{@code streakDays >= rule.streakDays}）：
 * 靠上面那个「一轮一张」的引用来保证第 8 天不重复发。取「恰好等于」会让某天的事件丢掉
 * （瞬时故障、重启）就永久漏发——而漏发是用户看得见、我们事后补不回来的那种错。
 * 多档位时**逐档各发一张**（V41 的种子只有「连续 7 天」一档，多档口径留给下一版定；
 * 月度阶梯那边是「只取达成的最高一档」，两者不是一回事，别照抄）。
 *
 * <p>与打卡发分一样，**本类不判断该不该发**（那在 {@link CheckInRewardRule} 的配置与
 * {@code CouponApi.issue} 的规则里），只负责把「谁、哪一档、哪一轮」翻译成一次发放。
 */
@Component
public class CheckInRewardListener {

    private static final Logger log = LoggerFactory.getLogger(CheckInRewardListener.class);

    /** 幂等引用的前缀：与来源码一起构成唯一键，便于在数据里一眼看出这是哪一次发放。 */
    private static final String SOURCE_PREFIX = "checkin-reward:";

    private final CheckInService checkInService;
    private final CheckInRewardRuleMapper ruleMapper;
    private final CouponApi couponApi;

    public CheckInRewardListener(CheckInService checkInService, CheckInRewardRuleMapper ruleMapper,
                                 CouponApi couponApi) {
        this.checkInService = checkInService;
        this.ruleMapper = ruleMapper;
        this.couponApi = couponApi;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCheckInSubmitted(CheckInSubmittedEvent event) {
        try {
            List<CheckInRewardRule> rules = ruleMapper.selectEnabled();
            if (rules.isEmpty()) {
                // 没配档位（或都被运营停用）：不发任何东西。静默返回是安全的——
                // 「打卡得券现在有没有档位」在运营后台的配置页上看得到，不需要每次打卡都写一条日志
                return;
            }
            CheckInStreak streak = checkInService.streak(event.userId(), event.petId());
            if (streak.streakDays() <= 0) {
                return;
            }
            LocalDate runStart = runStartOf(streak);
            for (CheckInRewardRule rule : rules) {
                // 逐档判定：达到就发（见类注释的「达到即发」）。一轮连续里同一档只发一次靠引用兜底
                if (rule.getStreakDays() != null && rule.getStreakDays() > 0
                        && streak.streakDays() >= rule.getStreakDays()) {
                    issue(event.userId(), rule, runStart);
                }
            }
        } catch (RuntimeException e) {
            // 降级：打卡已经提交成功，券可以在下一次打卡或人工补发时补上（同 CheckInPointsListener）
            log.warn("打卡得券失败（打卡已成功，券待补）：user={} pet={} 原因={}",
                    event.userId(), event.petId(), e.toString());
        }
    }

    /**
     * 这一轮连续的**起始日**——幂等引用的锚点（见类注释）。
     *
     * <p>由 {@link CheckInStreak} 的两个字段推出来（**不重算连续天数**）：
     * 今天打过卡，连续段从今天往前数 streak 天；今天还没打卡（今天未打卡不算断签），
     * 连续段从昨天往前数 streak 天。基准日与 {@link CheckInService#streak} 取的是同一个
     * （{@link AppTime#today()}），所以锚点与它算出的连续段严格对应。
     */
    private static LocalDate runStartOf(CheckInStreak streak) {
        LocalDate today = AppTime.today();
        return streak.checkedToday()
                ? today.minusDays(streak.streakDays() - 1L)
                : today.minusDays(streak.streakDays());
    }

    /**
     * 发这一档的券。**每档单独接异常**：一档配错（模板停用、id 写错）不该让同一轮里的其它档也不发。
     *
     * <p>发几张的引用拼法与 {@code MonthlyLadderJob} 一致：只有一张时引用不带序号
     * （与往期数据同名，便于人读），多张时逐张加序号。
     */
    private void issue(long userId, CheckInRewardRule rule, LocalDate runStart) {
        String sourceRef = SOURCE_PREFIX + userId + ":" + rule.getStreakDays() + ":" + runStart;
        int count = rule.getCouponCount() == null ? 1 : rule.getCouponCount();
        for (int i = 0; i < count; i++) {
            try {
                couponApi.issue(new CouponApi.IssueCommand(userId, rule.getCouponTemplateId(),
                        CouponApi.SOURCE_CHECK_IN_TASK,
                        count == 1 ? sourceRef : sourceRef + ":" + (i + 1),
                        null, "连续打卡 " + rule.getStreakDays() + " 天"));
            } catch (RuntimeException e) {
                log.warn("打卡得券失败（打卡已成功，券待补）：user={} 档位={}天 ref={} 原因={}",
                        userId, rule.getStreakDays(), sourceRef, e.toString());
            }
        }
    }
}
