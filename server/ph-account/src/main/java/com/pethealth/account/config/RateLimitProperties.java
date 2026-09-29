package com.pethealth.account.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * 接口限流的规则表（ADR-0028）。
 *
 * <p>阈值是**技术参数**——改要重启，所以走配置不进库（ADR-0010）；且按下面「谁改」的判断，
 * 它不该由运营在后台调：运营要调的是 AI 额度与提醒阈值这类业务可调项。
 *
 * <p>规则的匹配是**按顺序取第一个前缀命中**的，所以顺序即优先级：把严的写前面。
 * 没有任何规则命中时**不限流**——这条是有意的：限流的目标是防刷，不是给新接口埋一个
 * 「忘了配规则就被限死」的坑；代价是漏配规则等于不限流，所以 {@code /api/v1/} 的兜底规则必须在。
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(Boolean enabled, List<Rule> rules) {

    /**
     * 默认规则：认证接口严、其余宽。
     *
     * <p>认证接口给 30/分钟而不是文档 6.3 的「1 分钟 1 次」：那个数字是给**短信验证码**定的
     * （发一条要花钱，所以要卡死），而现在认证是手机号 + 密码（ADR-0027），密钥校验是本地算力，
     * 用户输错两次也就一顿重试。真正的账号维度防刷仍由 {@code LoginThrottle} 兜着。
     *
     * <p>兜底给 600/分钟是「不误伤」优先：共享出口（公司 NAT）后面可能坐着整个办公室。
     */
    private static final List<Rule> DEFAULT_RULES = List.of(
            new Rule("/api/v1/app/auth/", 30, Duration.ofMinutes(1)),
            new Rule("/api/v1/", 600, Duration.ofMinutes(1)));

    public RateLimitProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        rules = rules == null || rules.isEmpty() ? DEFAULT_RULES : List.copyOf(rules);
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    /**
     * 找第一个前缀命中的规则。
     *
     * @param path 请求路径（不含 query）
     * @return 命中的规则；都不命中返回 {@code null}，表示不限流
     */
    public Rule ruleFor(String path) {
        for (Rule rule : rules) {
            if (path.startsWith(rule.pathPrefix())) {
                return rule;
            }
        }
        return null;
    }

    /**
     * 一条限流规则。
     *
     * @param pathPrefix 路径前缀；空串会匹配所有路径（所以兜底规则用 {@code /api/v1/} 而不是空串，
     *                   免得把 actuator 这类非业务路径也圈进来）
     * @param limit      窗口内允许的次数
     * @param window     窗口长度
     */
    public record Rule(String pathPrefix, Integer limit, Duration window) {

        public Rule {
            pathPrefix = pathPrefix == null ? "" : pathPrefix;
            // 配错（0 或负数）按「不限制」之外的最近一档处理：给 1 比给「无限」安全，
            // 也比「全部拒绝」合理——写错一个数字不该让接口不可用
            limit = limit == null || limit <= 0 ? 1 : limit;
            window = window == null ? Duration.ofMinutes(1) : window;
        }
    }
}
