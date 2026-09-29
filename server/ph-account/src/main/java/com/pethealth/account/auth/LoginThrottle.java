package com.pethealth.account.auth;

import com.pethealth.account.config.AuthProperties;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 登录失败限流（交付文档 6.3「限流 / 防刷」）。
 *
 * <p>按手机号计数而不是按 IP：家用网络与公司出口常常是同一个 IP，
 * 按 IP 会误伤一整栋楼的人；而拿手机号做撞库，针对的正是账号本身。
 *
 * <p>计数用 Redis 的 {@code INCR} + 窗口 TTL，天然并发安全；成功登录即清零。
 *
 * <p>两条纪律：计数器的值**只当数字用之前先验一验**（见 {@link #countOf}），
 * 以及**限流器坏了不能把登录一起带走**——这个类在登录主路径上，
 * 任何未接住的异常都会变成用户看到的 50000。
 */
@Service
public class LoginThrottle {

    private static final Logger log = LoggerFactory.getLogger(LoginThrottle.class);

    private static final String KEY_PREFIX = "ph:auth:login-fail:";

    private final StringRedisTemplate redis;
    private final int maxFailures;
    private final Duration window;

    public LoginThrottle(StringRedisTemplate redis, AuthProperties properties) {
        this.redis = redis;
        this.maxFailures = properties.loginMaxFailures();
        this.window = properties.loginLockWindow();
    }

    /** 验证密码之前先问一句：这个号是不是已经被试太多次了。 */
    public void checkAllowed(String phoneHash) {
        String value = redis.opsForValue().get(key(phoneHash));
        if (value != null && countOf(value) >= maxFailures) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                    "尝试次数过多，请 " + window.toMinutes() + " 分钟后再试");
        }
    }

    public void recordFailure(String phoneHash) {
        String key = key(phoneHash);
        Long count = increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, window);
        }
    }

    public void clear(String phoneHash) {
        redis.delete(key(phoneHash));
    }

    /**
     * 自增计数，键被写坏时重置后重试。
     *
     * <p>Redis 的 {@code INCR} 遇到非数字内容会直接报错，而这里在登录主路径上——
     * 一个被人工排障或旧版本写入污染的键，不该让所有人都登不上。重置等价于「计数从头开始」，
     * 并且会留一条 WARN。重试仍失败就放弃计数（登录继续），可用性优先于这一次的限流精度。
     *
     * @param key 计数键
     * @return 自增后的值；实在拿不到就是 null
     */
    private Long increment(String key) {
        try {
            return redis.opsForValue().increment(key);
        } catch (RuntimeException e) {
            log.warn("登录失败计数自增失败，重置该键后重试：key={}", key, e);
            redis.delete(key);
        }
        try {
            return redis.opsForValue().increment(key);
        } catch (RuntimeException e) {
            log.warn("登录失败计数仍无法自增，本轮不计入限流：key={}", key, e);
            return null;
        }
    }

    /**
     * 把 Redis 里的值读成计数。
     *
     * <p>**不直接用 {@code Integer.parseInt}**：值不是数字时它会抛 {@code NumberFormatException}，
     * 从登录路径冒到兜底处理器就是 50000——用户看到「服务器内部错误」，
     * 而真实原因只是一个计数键被写坏了。读不懂按 0 处理（放行）并留痕：
     * 能写这个键的人已经拿到了 Redis 的写权限，为它把所有人挡在门外不划算。
     */
    private int countOf(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.warn("登录失败计数不是数字，按 0 处理：value={}", value);
            return 0;
        }
    }

    private String key(String phoneHash) {
        return KEY_PREFIX + phoneHash;
    }
}
