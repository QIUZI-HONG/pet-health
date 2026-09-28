package com.pethealth.reminder.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pethealth.common.time.AppTime;
import com.pethealth.record.api.ReminderSourceApi;
import com.pethealth.reminder.domain.Message;
import com.pethealth.reminder.domain.ReminderRule;
import com.pethealth.reminder.domain.ReminderSetting;
import com.pethealth.reminder.mapper.MessageMapper;
import com.pethealth.reminder.mapper.ReminderSettingMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 提醒生成（切片 #99，规则与阈值见 ADR-0019）。
 *
 * <p>五类提醒各有触发条件，但共用一条纪律：**幂等键 = 宠物 + 类型 + 窗口**。
 * 同键再次生成是**更新内容**（疫苗从「还有 7 天」到「还有 6 天」是同一条在变），
 * 而不是把消息中心刷成一堆重复项。这个幂等键落成唯一索引（`uk_dedup`），
 * 所以并发跑批算也不会重复。
 *
 * <p>阈值全部从 `reminder_rule` 读（ADR-0010：业务可调项入库），代码里只留兜底默认值。
 */
@Service
public class ReminderGenerator {

    private static final Logger log = LoggerFactory.getLogger(ReminderGenerator.class);

    /** 每日批算的时间点（ADR-0019：08:00 Asia/Shanghai）。 */
    public static final String DAILY_CRON = "0 0 8 * * *";

    /**
     * 每宠物每天提醒上限的兜底值。**实际值从 {@code reminder_rule} 的全局策略行读**（ADR-0019 / ADR-0010：
     * 阈值入库，运营可调）；代码里这个常量只在规则缺失时兜底。
     */
    public static final int FALLBACK_MAX_PER_PET_PER_DAY = 3;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy年MM月dd日");

    private final MessageMapper messageMapper;
    private final ReminderSettingMapper settingMapper;
    private final ReminderRuleService ruleService;
    private final ReminderSourceApi sourceApi;

    public ReminderGenerator(MessageMapper messageMapper,
                             ReminderSettingMapper settingMapper,
                             ReminderRuleService ruleService,
                             ReminderSourceApi sourceApi) {
        this.messageMapper = messageMapper;
        this.settingMapper = settingMapper;
        this.ruleService = ruleService;
        this.sourceApi = sourceApi;
    }

    /** 每日批算：遍历所有有宠物的用户（用户量上来后要分批，见 ADR-0019 的说明）。 */
    @Transactional
    public int generateForAllUsers() {
        int created = 0;
        for (Long userId : sourceApi.listActiveUserIds()) {
            created += materialize(userId);
        }
        log.info("每日提醒批算完成：用户 {} 个，新增/更新提醒 {} 条", sourceApi.listActiveUserIds().size(), created);
        return created;
    }

    /**
     * 补算某个用户的提醒（幂等）。
     *
     * <p>三处会调它：每日批算、用户读消息中心/首页时（惰性补算，保证打开时是最新的）、
     * 打卡写入后（异常提醒即时生成）。
     */
    @Transactional
    public int materialize(long userId) {
        int affected = 0;
        for (ReminderSourceApi.PetBrief pet : sourceApi.listPetsOf(userId)) {
            affected += generateForPet(pet, AppTime.today());
        }
        return affected;
    }

    /**
     * 单只宠物的全部规则。返回处理条数（含更新）。
     *
     * @param today 业务日期。传参数而不是在方法里取「今天」，测试才能构造历史场景。
     */
    @Transactional
    public int generateForPet(ReminderSourceApi.PetBrief pet, LocalDate today) {
        // 规则与开关一次读齐：原先每类各查一次库，一只宠物五类就是五次，批算遍历用户时是 N×5
        Map<Integer, ReminderRule> rules = ruleService.loadAll();
        Map<Integer, Boolean> switches = switchesOf(pet.userId(), rules);

        Map<Integer, Candidate> candidates = new LinkedHashMap<>();
        if (switches.getOrDefault(Message.TYPE_VACCINE, false)) {
            vaccines(pet, today, rules).ifPresent(candidate -> candidates.put(Message.TYPE_VACCINE, candidate));
        }
        if (switches.getOrDefault(Message.TYPE_DEWORM, false)) {
            deworm(pet, today, rules).ifPresent(candidate -> candidates.put(Message.TYPE_DEWORM, candidate));
        }
        if (switches.getOrDefault(Message.TYPE_DAILY, false)) {
            daily(pet, today).ifPresent(candidate -> candidates.put(Message.TYPE_DAILY, candidate));
        }
        if (switches.getOrDefault(Message.TYPE_TREND, false)) {
            trend(pet, today, rules).ifPresent(candidate -> candidates.put(Message.TYPE_TREND, candidate));
        }
        if (switches.getOrDefault(Message.TYPE_CHRONIC_ELDERLY, false)) {
            chronicOrElderly(pet, today, rules)
                    .ifPresent(candidate -> candidates.put(Message.TYPE_CHRONIC_ELDERLY, candidate));
        }

        int cap = ruleService.intConfig(rules, ReminderRuleService.GLOBAL_TYPE, "maxPerPetPerDay",
                FALLBACK_MAX_PER_PET_PER_DAY);
        // 上限口径是「**今天新生成几条**」（按 created_at），不是「业务日期是今天」——
        // 补录会把业务日期填到过去，若按业务日期算，补 5 天就能堆出 5 条（踩过）。
        int remaining = Math.max(0, cap - (int) createdToday(pet.userId(), pet.petId()));
        List<Candidate> kept = candidates.values().stream()
                .sorted((a, b) -> a.remindAt().compareTo(b.remindAt()))
                .limit(remaining)
                .toList();
        if (candidates.size() > kept.size()) {
            // 超出上限的按紧迫度丢弃。**不做「今天还有 N 条」汇总条**——消息中心本身按时间列出全部，
            // 汇总条只会多一条还要点开的噪音（这条与 ADR-0019 初稿的措辞不同，已在 ADR 里更正）。
            log.info("宠物 {} 今日候选提醒 {} 条，超过上限 {}，按紧迫度保留 {} 条",
                    pet.petId(), candidates.size(), cap, kept.size());
        }

        int affected = 0;
        for (Candidate candidate : kept) {
            affected += upsert(pet, candidate);
        }
        return affected;
    }

    /** 打卡里标注异常时即时生成（写入路径调用，见 CheckInRecordedListener）。 */
    @Transactional
    public void onAbnormalCheckIn(long userId, long petId, String petName, LocalDate recordDate) {
        Map<Integer, ReminderRule> rules = ruleService.loadAll();
        if (!switchesOf(userId, rules).getOrDefault(Message.TYPE_ABNORMAL, false)) {
            return;
        }
        ReminderSourceApi.PetBrief pet = sourceApi.findPet(petId);
        if (pet == null) {
            return;
        }
        // 上限对即时路径同样生效：打卡可以补录 7 天，不设限就能一天堆出七八条异常提醒
        int cap = ruleService.intConfig(rules, ReminderRuleService.GLOBAL_TYPE, "maxPerPetPerDay",
                FALLBACK_MAX_PER_PET_PER_DAY);
        if (createdToday(userId, petId) >= cap) {
            log.info("宠物 {} 今日提醒已达上限 {}，跳过异常提醒的即时生成", petId, cap);
            return;
        }
        Candidate candidate = new Candidate(
                Message.KIND_REMINDER,
                Message.TYPE_ABNORMAL,
                "记录了异常情况",
                petName + " 在" + recordDate.format(DATE) + "的打卡里被标注为异常。建议继续观察；"
                        + "如果情况持续或加重，请尽快咨询兽医。",
                Message.RISK_YELLOW,
                recordDate.atStartOfDay(),
                petId + ":" + Message.TYPE_ABNORMAL + ":" + recordDate,
                "去记录",
                "/",
                pet);
        upsert(pet, candidate);
    }

    // ---------------------------------------------------------------- 各类规则

    /**
     * 疫苗：到期前 N 天开始提醒（N 从规则表读，默认 7）。
     *
     * <p>窗口**要往前含宽限期**：已经过期但还没处理的疫苗恰恰是最该提醒的，
     * 只查 [今天, 今天+N] 会把它们漏掉（踩过一次）。宽限期默认 30 天——
     * 过期一个月还不处理，说明用户已经决定不打，继续提醒只是骚扰。
     */
    private Optional<Candidate> vaccines(ReminderSourceApi.PetBrief pet, LocalDate today,
                                         Map<Integer, ReminderRule> rules) {
        int advanceDays = ruleService.intConfig(rules, Message.TYPE_VACCINE, "advanceDays",
                ReminderRuleService.DEFAULT_ADVANCE_DAYS);
        int graceDays = ruleService.intConfig(rules, Message.TYPE_VACCINE, "overdueGraceDays",
                ReminderRuleService.DEFAULT_OVERDUE_GRACE_DAYS);
        var dueItems = sourceApi.dueItems(pet.petId(), today.minusDays(graceDays), today.plusDays(advanceDays));
        return dueItems.stream()
                .filter(item -> item.kind() == 1)
                .findFirst()
                .map(item -> dueCandidate(pet, item, Message.TYPE_VACCINE, "疫苗", today));
    }

    /** 驱虫：同疫苗，类型不同。 */
    private Optional<Candidate> deworm(ReminderSourceApi.PetBrief pet, LocalDate today,
                                       Map<Integer, ReminderRule> rules) {
        int advanceDays = ruleService.intConfig(rules, Message.TYPE_DEWORM, "advanceDays",
                ReminderRuleService.DEFAULT_ADVANCE_DAYS);
        int graceDays = ruleService.intConfig(rules, Message.TYPE_DEWORM, "overdueGraceDays",
                ReminderRuleService.DEFAULT_OVERDUE_GRACE_DAYS);
        var dueItems = sourceApi.dueItems(pet.petId(), today.minusDays(graceDays), today.plusDays(advanceDays));
        return dueItems.stream()
                .filter(item -> item.kind() == 2)
                .findFirst()
                .map(item -> dueCandidate(pet, item, Message.TYPE_DEWORM, "驱虫", today));
    }

    private Candidate dueCandidate(ReminderSourceApi.PetBrief pet,
                                   ReminderSourceApi.DueItem item,
                                   int type,
                                   String label,
                                   LocalDate today) {
        // 用入参 today 而不是「系统今天」：这个参数存在的唯一理由就是让历史场景可测
        long days = java.time.temporal.ChronoUnit.DAYS.between(today, item.dueOn());
        String title = days <= 0
                ? pet.name() + "的" + label + "已到期"
                : pet.name() + "的" + label + "还有 " + days + " 天";
        String content = label + "「" + item.name() + "」应在 " + item.dueOn().format(DATE) + " 前后完成。"
                + "到店前可以先在服务页看看附近可预约的机构。";
        // 到期日作为幂等窗口：同一条记录在到期前每天只更新文案，不新增
        return new Candidate(Message.KIND_REMINDER, type, title, content, Message.RISK_YELLOW,
                item.dueOn().atStartOfDay(), pet.petId() + ":" + type + ":" + item.dueOn(),
                "找服务", "/services", pet);
    }

    /**
     * 日常：当天还没有任何记录时提醒一次（每天最多一条）。
     *
     * <p>这是 F007 的「日常」类，ADR-0019 把它列进首期。它不制造焦虑——文案是「今天还没记录」而不是
     * 「你的宠物有异常」，动作直指打卡。
     */
    private Optional<Candidate> daily(ReminderSourceApi.PetBrief pet, LocalDate today) {
        if (sourceApi.hasRecordOn(pet.petId(), today)) {
            return Optional.empty();
        }
        // 撤销该宠物**未读的旧日常提醒**：不活跃用户回来后不该看到三十条「今天还没有记录」。
        // 已读的那些留着当历史（用户确实看到过）。
        messageMapper.delete(Wrappers.<Message>lambdaQuery()
                .eq(Message::getPetId, pet.petId())
                .eq(Message::getType, Message.TYPE_DAILY)
                .eq(Message::getStatus, Message.STATUS_SENT)
                .lt(Message::getRemindAt, today.atStartOfDay()));
        return Optional.of(new Candidate(
                Message.KIND_REMINDER,
                Message.TYPE_DAILY,
                pet.name() + "今天还没有记录",
                "花几秒钟记一下体重、饮食、排泄这些日常项，评分和趋势才有依据。",
                Message.RISK_GREEN,
                today.atStartOfDay(),
                pet.petId() + ":" + Message.TYPE_DAILY + ":" + today,
                "去打卡",
                "/",
                pet));
    }

    /** 趋势：近 N 天体重变化幅度 ≥ 阈值时提醒（阈值入库）。 */
    private Optional<Candidate> trend(ReminderSourceApi.PetBrief pet, LocalDate today,
                                      Map<Integer, ReminderRule> rules) {
        int windowDays = ruleService.intConfig(rules, Message.TYPE_TREND, "windowDays",
                ReminderRuleService.DEFAULT_WINDOW_DAYS);
        int percent = ruleService.intConfig(rules, Message.TYPE_TREND, "weightChangePercent",
                ReminderRuleService.DEFAULT_WEIGHT_CHANGE_PERCENT);

        ReminderSourceApi.WeightRange range = sourceApi.weightRange(pet.petId(),
                today.minusDays(windowDays - 1L), today);
        if (range == null || range.min() == null || range.max().signum() == 0) {
            return Optional.empty();
        }
        BigDecimal change = range.max().subtract(range.min())
                .divide(range.min(), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        if (change.abs().compareTo(BigDecimal.valueOf(percent)) < 0) {
            return Optional.empty();
        }
        boolean up = change.signum() > 0;
        String title = pet.name() + "的体重" + (up ? "上升" : "下降") + "了 " + change.abs().setScale(0, RoundingMode.HALF_UP) + "%";
        String content = "近 " + windowDays + " 天体重从 " + range.min().toPlainString() + " kg 变化到 "
                + range.max().toPlainString() + " kg。短期波动可能只是测量差异；持续变化建议咨询兽医。";
        // 幂等窗口 = 「本周期」：同一个月内只提醒一次（否则每天都会生成一条）
        String window = today.getYear() + "-" + today.getMonthValue();
        return Optional.of(new Candidate(Message.KIND_REMINDER, Message.TYPE_TREND, title, content,
                Message.RISK_YELLOW, today.atStartOfDay(),
                pet.petId() + ":" + Message.TYPE_TREND + ":" + window,
                "看档案", "/records", pet));
    }

    /** 慢病 / 老年照护：年龄 ≥7 岁或有慢病时，按间隔（默认 3 个月）提醒一次复查。 */
    private Optional<Candidate> chronicOrElderly(ReminderSourceApi.PetBrief pet, LocalDate today,
                                                  Map<Integer, ReminderRule> rules) {
        boolean elderly = pet.birthday() != null
                && Period.between(pet.birthday(), today).getYears() >= 7;
        if (!elderly && !pet.chronic()) {
            // 正常的「这条规则不适用」，返回空 Optional——**不能 return null**：
            // 调用方是 .ifPresent(...)，null 会让整个消息中心 500（踩过两次）
            return Optional.empty();
        }
        int intervalMonths = ruleService.intConfig(rules, Message.TYPE_CHRONIC_ELDERLY, "intervalMonths",
                ReminderRuleService.DEFAULT_INTERVAL_MONTHS);
        // 幂等窗口 = 按间隔划出的周期（例如每 3 个月一个窗口），保证同一周期只提醒一次
        int periodIndex = (today.getYear() * 12 + today.getMonthValue()) / Math.max(1, intervalMonths);
        String reason = pet.chronic() ? "有慢病记录" : "已进入老年期";
        String title = pet.name() + "该做一次复查了";
        String content = pet.name() + reason + "，建议每 " + intervalMonths + " 个月做一次体检或复诊，"
                + "把最近的记录一起带给医生看。";
        return Optional.of(new Candidate(Message.KIND_REMINDER, Message.TYPE_CHRONIC_ELDERLY,
                title, content, Message.RISK_GREEN, today.atStartOfDay(),
                pet.petId() + ":" + Message.TYPE_CHRONIC_ELDERLY + ":" + periodIndex,
                "找服务", "/services", pet));
    }

    // ---------------------------------------------------------------- 幂等写入

    /**
     * 同键更新、异键新增。
     *
     * <p>更新时**不动 status / read_at**：用户已经读过的提醒不该因为文案更新又变回未读
     * （否则每天批算都会把已读刷成未读，未读角标永远是红的）。
     */
    /** 今天已经为这只宠物生成了几条提醒（上限的判定依据，见 generateForPet 的注释）。 */
    private long createdToday(long userId, long petId) {
        return messageMapper.selectCount(Wrappers.<Message>lambdaQuery()
                .eq(Message::getUserId, userId)
                .eq(Message::getPetId, petId)
                .ge(Message::getCreatedAt, AppTime.today().atStartOfDay()));
    }

    private int upsert(ReminderSourceApi.PetBrief pet, Candidate candidate) {
        Message existing = messageMapper.selectOne(Wrappers.<Message>lambdaQuery()
                .eq(Message::getDedupKey, candidate.dedupKey()));
        if (existing != null) {
            existing.setTitle(candidate.title());
            existing.setContent(candidate.content());
            existing.setRiskLevel(candidate.riskLevel());
            existing.setRemindAt(candidate.remindAt());
            existing.setActionHint(candidate.actionHint());
            existing.setActionTarget(candidate.actionTarget());
            messageMapper.updateById(existing);
            return 1;
        }

        Message message = new Message();
        message.setUserId(pet.userId());
        message.setPetId(candidate.pet().petId());
        message.setKind(candidate.kind());
        message.setType(candidate.type());
        message.setTitle(candidate.title());
        message.setContent(candidate.content());
        message.setRiskLevel(candidate.riskLevel());
        message.setRemindAt(candidate.remindAt());
        message.setStatus(Message.STATUS_SENT);
        message.setDedupKey(candidate.dedupKey());
        message.setActionHint(candidate.actionHint());
        message.setActionTarget(candidate.actionTarget());
        message.setChannelState("in_site");
        messageMapper.insert(message);
        return 1;
    }

    /**
     * 一次读齐某个用户对各提醒类型的开关。
     *
     * <p>默认开（没设置过就是开）；**平台总开关关掉时也算关**——运营停掉某类时用户也开不起来。
     */
    private Map<Integer, Boolean> switchesOf(long userId, Map<Integer, ReminderRule> rules) {
        Map<Integer, ReminderSetting> stored = new LinkedHashMap<>();
        for (ReminderSetting setting : settingMapper.selectList(Wrappers.<ReminderSetting>lambdaQuery()
                .eq(ReminderSetting::getUserId, userId))) {
            stored.put(setting.getType(), setting);
        }
        Map<Integer, Boolean> switches = new LinkedHashMap<>();
        for (int type : List.of(Message.TYPE_VACCINE, Message.TYPE_DEWORM, Message.TYPE_DAILY,
                Message.TYPE_ABNORMAL, Message.TYPE_TREND, Message.TYPE_CHRONIC_ELDERLY)) {
            ReminderSetting setting = stored.get(type);
            boolean userOn = setting == null || setting.isOn();
            switches.put(type, userOn && ruleService.platformEnabled(rules, type));
        }
        return switches;
    }

    /** 一条待生成的提醒（生成规则内部用的中间结构）。 */
    private record Candidate(
            int kind,
            int type,
            String title,
            String content,
            int riskLevel,
            LocalDateTime remindAt,
            String dedupKey,
            String actionHint,
            String actionTarget,
            ReminderSourceApi.PetBrief pet) {
    }
}
