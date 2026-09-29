package com.pethealth.account.auth;

import com.pethealth.account.config.RateLimitProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 固定窗口计数器（ADR-0028）。与 {@code LoginThrottle} 同一手法：{@code INCR} + 首次 {@code EXPIRE}。
 *
 * <p>为什么不再用 Lua/滑动窗口：限流在这里的目标是**防刷**，不是精确整形。固定窗口实现最简、
 * 行为最可解释，代价是窗口边界前后各打满一次会有双倍突发——这个代价写在 ADR-0028 里。
 *
 * <p><b>Redis 不可用时放行</b>（返回 {@code empty}，记一条 WARN）：限流是防滥用，不是鉴权。
 * 让它把整个 API 拖成 500，代价远大于「这一小段时间没限流」。这与「红线词表读不到就不放行」
 * 是两种东西——那一层失效会漏掉急症，这一层失效只是少了一道防刷。
 * （这个取舍写在 ADR-0028 的「限流器自己故障时怎么办」一节；引用 ADR 时请核对那一节确实写了，
 * 别拿审计写入失败那条来当依据——它说的是另一件事。）
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private static final String KEY_PREFIX = "ph:rate:";

    private final StringRedisTemplate redis;

    public RateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 记一次请求。
     *
     * @param rule    命中的规则。计数键**带上规则前缀**：不同规则各有各的预算，
     *                否则匿名流量会花掉本该留给登录那 30 次的那个计数器；
     *                而且规则各自声明 window，共用一个键时先来的那条 window 会主导整个键的寿命
     * @param subject 计数主体（已登录是 {@code u:<userId>}，未登录是 {@code ip:<addr>}）
     * @return 该主体在当前窗口内**含本次**的请求数与窗口剩余时间；Redis 不可用时为空
     */
    public Optional<Window> hit(RateLimitProperties.Rule rule, String subject) {
        // 前缀里本来带 / 与 : 的规则不多，统一换成下划线避免键长得难读（只影响可读性，不影响语义）
        String key = KEY_PREFIX + rule.pathPrefix().replace('/', '_') + ":" + subject;
        Duration window = rule.window();
        try {
            Long count = redis.opsForValue().increment(key);
            if (count == null) {
                return Optional.empty();
            }
            if (count == 1L) {
                // 只在第一次设 TTL：之后每次重设会让窗口被持续刷新，变成「只要不断请求就永不过期」
                redis.expire(key, window);
            }
            Long ttlSeconds = redis.getExpire(key, TimeUnit.SECONDS);
            if (ttlSeconds == null || ttlSeconds < 0) {
                // **没有 TTL 的键必须补一个**：上面那两行的间隙里进程挂掉、或 expire 本身失败，
                // 都会留下一个永不过期的计数键——那意味着这个主体被**永久**限流（实测过这个形状）。
                // 补 TTL 是自愈，代价只是这一次窗口从当下重新计时
                redis.expire(key, window);
                return Optional.of(new Window(count, window));
            }
            return Optional.of(new Window(count, Duration.ofSeconds(ttlSeconds)));
        } catch (RuntimeException e) {
            log.warn("限流计数不可用，本次不限制：rule={} subject={}", rule.pathPrefix(), subject, e);
            return Optional.empty();
        }
    }

    /**
     * 一次请求之后的窗口状态。
     *
     * @param count 窗口内已计次数（含本次）
     * @param retryAfter 窗口剩余时间，用于告知调用方「多久之后再来」
     */
    public record Window(long count, Duration retryAfter) {
    }
}
