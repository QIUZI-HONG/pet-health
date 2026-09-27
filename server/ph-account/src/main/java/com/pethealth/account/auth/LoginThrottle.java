package com.pethealth.account.auth;

import com.pethealth.account.config.AuthProperties;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.error.ErrorCode;
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
 */
@Service
public class LoginThrottle {

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
        if (value != null && Integer.parseInt(value) >= maxFailures) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                    "尝试次数过多，请 " + window.toMinutes() + " 分钟后再试");
        }
    }

    public void recordFailure(String phoneHash) {
        String key = key(phoneHash);
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, window);
        }
    }

    public void clear(String phoneHash) {
        redis.delete(key(phoneHash));
    }

    private String key(String phoneHash) {
        return KEY_PREFIX + phoneHash;
    }
}
