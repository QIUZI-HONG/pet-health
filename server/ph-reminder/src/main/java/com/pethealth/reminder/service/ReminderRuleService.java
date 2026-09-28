package com.pethealth.reminder.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pethealth.reminder.domain.ReminderRule;
import com.pethealth.reminder.mapper.ReminderRuleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 提醒规则与阈值的读取（ADR-0010：业务可调项入库，不写死在代码里）。
 *
 * <p>阈值放在 `reminder_rule.config` 的 JSON 里，各类型自己的键不同。**解析失败按默认值处理并记日志**——
 * 规则配错不该让整个提醒体系不工作（禁止吞异常的反面：这里要吞掉并留下痕迹，因为它是配置错误，
 * 不是业务失败）。
 */
@Service
public class ReminderRuleService {

    private static final Logger log = LoggerFactory.getLogger(ReminderRuleService.class);

    /** 全局策略行的类型号（不是提醒类型）：目前只放「每宠物每日上限」这类跨类型参数。 */
    public static final int GLOBAL_TYPE = 0;

    /** 各类规则的默认阈值：与迁移脚本里的初始值一致，也是解析失败时的兜底。 */
    public static final int DEFAULT_ADVANCE_DAYS = 7;
    public static final int DEFAULT_WEIGHT_CHANGE_PERCENT = 5;
    public static final int DEFAULT_WINDOW_DAYS = 7;
    public static final int DEFAULT_INTERVAL_MONTHS = 3;
    /** 已经过期的防疫记录继续提醒多少天（过期不处理就不该一直骚扰，但也不能一过期就闭嘴）。 */
    public static final int DEFAULT_OVERDUE_GRACE_DAYS = 30;
    /** 每宠物每天的提醒上限（防骚扰）。放全局策略行里，运营可调（ADR-0019）。 */
    public static final int DEFAULT_MAX_PER_PET_PER_DAY = 3;

    private final ReminderRuleMapper ruleMapper;

    public ReminderRuleService(ReminderRuleMapper ruleMapper) {
        this.ruleMapper = ruleMapper;
    }

    public List<ReminderRule> all() {
        return ruleMapper.selectList(Wrappers.<ReminderRule>lambdaQuery().orderByAsc(ReminderRule::getType));
    }

    /**
     * 一次把规则读成 map，供一次生成过程复用。
     *
     * <p>为什么这么做：原先每个类型各查一次库，一只宠物跑五类规则就是五次查询，
     * 每日批算遍历用户时是 N×5——而规则表是「运营偶尔改一次」的配置，一次生成读一次就够。
     */
    public Map<Integer, ReminderRule> loadAll() {
        Map<Integer, ReminderRule> rules = new HashMap<>();
        for (ReminderRule rule : all()) {
            rules.put(rule.getType(), rule);
        }
        return rules;
    }

    /** 从已加载的规则里取整数参数（批量路径用，避免重复查库）。 */
    public int intConfig(Map<Integer, ReminderRule> rules, int type, String key, int defaultValue) {
        ReminderRule rule = rules.get(type);
        if (rule == null || rule.getConfig() == null) {
            return defaultValue;
        }
        Object value = parse(rule.getConfig()).get(key);
        return value instanceof Number number ? number.intValue() : defaultValue;
    }

    /** 从已加载的规则里判断平台是否启用了某类。 */
    public boolean platformEnabled(Map<Integer, ReminderRule> rules, int type) {
        ReminderRule rule = rules.get(type);
        return rule == null || rule.isEnabled();
    }

    /** 平台级总开关：运营关掉某类时，用户也开不起来。 */
    public boolean platformEnabled(int type) {
        ReminderRule rule = find(type);
        return rule == null || rule.isEnabled();
    }

    /** 取某类规则的某个整数参数，取不到就用默认值。 */
    public int intConfig(int type, String key, int defaultValue) {
        ReminderRule rule = find(type);
        if (rule == null || rule.getConfig() == null) {
            return defaultValue;
        }
        Map<String, Object> config = parse(rule.getConfig());
        Object value = config.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return defaultValue;
    }

    private ReminderRule find(int type) {
        return ruleMapper.selectOne(Wrappers.<ReminderRule>lambdaQuery().eq(ReminderRule::getType, type));
    }

    /** 极简 JSON 解析：只认顶层的基本类型键值，不引 JSON 库（结构是我们自己约定的）。 */
    private Map<String, Object> parse(String json) {
        Map<String, Object> result = new HashMap<>();
        String body = json.trim();
        if (body.startsWith("{")) {
            body = body.substring(1);
        }
        if (body.endsWith("}")) {
            body = body.substring(0, body.length() - 1);
        }
        for (String pair : body.split(",")) {
            String[] kv = pair.split(":", 2);
            if (kv.length != 2) {
                continue;
            }
            String key = kv[0].trim().replace("\"", "");
            String raw = kv[1].trim();
            if (raw.isEmpty() || "\"\"".equals(raw)) {
                continue;
            }
            try {
                result.put(key, Integer.valueOf(raw.replace("\"", "")));
            } catch (NumberFormatException e) {
                // 非数字参数（本期没有）忽略即可，但要留痕，别静默
                log.warn("提醒规则参数不是整数，已忽略：key={} value={}", key, raw);
            }
        }
        return result;
    }
}
