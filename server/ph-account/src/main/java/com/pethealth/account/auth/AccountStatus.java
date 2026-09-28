package com.pethealth.account.auth;

import com.pethealth.account.domain.User;
import com.pethealth.account.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 账号可用性：回答「这个 userId 现在还算登录着吗」。
 *
 * <p>规则只有一条：**已注销（禁用）的账号等于未登录**。注销之后签发的旧 Access Token
 * 在到期前签名仍然有效，如果不额外判一次状态，它就能继续读消息、继续导出——
 * 而这件事只有「碰巧查过 users 表」的路径（`/users/me`）做对了，其余模块全靠自觉
 * （2026-09-28 测试报告 D13：注销后的令牌仍能读 `/messages` 与 `/users/me/export`）。
 *
 * <p>所以判在**鉴权层一处**（`JwtAuthenticationFilter`），业务代码不必各自记得查状态。
 *
 * <p>代价是每个已登录请求要判一次，这正是 #121 当时缓着没做的原因——所以这里不让它变成
 * 每请求一次库查询：把「禁用」当成一个小集合放 Redis，正常用户只花一次 Redis 命中；
 * Redis 里没有答案时才查库，并把结论缓存 {@link #POSITIVE_TTL}。
 * 缓存的代价写在 {@link #isActive} 的注释里，别当成永久真相。
 */
@Component
public class AccountStatus {

    private static final Logger log = LoggerFactory.getLogger(AccountStatus.class);

    private static final String KEY_PREFIX = "ph:auth:status:";
    /** 「可用」的短缓存：让直接改库禁用（绕开本代码）也能在一分钟内生效。 */
    private static final Duration POSITIVE_TTL = Duration.ofSeconds(60);
    /** 「禁用」的长缓存：注销后不再用改回来，足够久即可（到期后由查库兜底）。 */
    private static final Duration DISABLED_TTL = Duration.ofDays(30);
    private static final String ACTIVE = "active";
    private static final String DISABLED = "disabled";

    private final StringRedisTemplate redis;
    private final UserMapper userMapper;

    public AccountStatus(StringRedisTemplate redis, UserMapper userMapper) {
        this.redis = redis;
        this.userMapper = userMapper;
    }

    /**
     * 这个账号现在能不能算「已登录」。
     *
     * <p>缓存只影响**新鲜度**，不影响结论的方向：注销走 {@link #markDisabled} 立刻写 Redis，
     * 所以用户自己的注销是即时生效的；只有「绕过本代码直接改库」才可能多活
     * {@link #POSITIVE_TTL}（一分钟）。反过来，Redis 挂了会退回查库，不会把所有人拒之门外。
     */
    public boolean isActive(long userId) {
        try {
            String cached = redis.opsForValue().get(key(userId));
            if (DISABLED.equals(cached)) {
                return false;
            }
            if (ACTIVE.equals(cached)) {
                return true;
            }
        } catch (DataAccessException e) {
            // Redis 抖一下不该让所有在线用户被登出：退回查库（慢，但结论正确）
            log.warn("账号状态缓存不可用，退回查库 user_id={}", userId, e);
            return activeInDb(userId);
        }

        boolean active = activeInDb(userId);
        try {
            redis.opsForValue().set(key(userId), active ? ACTIVE : DISABLED,
                    active ? POSITIVE_TTL : DISABLED_TTL);
        } catch (DataAccessException e) {
            log.warn("账号状态写缓存失败（不影响本次结论）user_id={}", userId, e);
        }
        return active;
    }

    /** 注销时调一次：让已签发的 Access Token 立刻作废（不等它的 2 小时过期）。 */
    public void markDisabled(long userId) {
        try {
            redis.opsForValue().set(key(userId), DISABLED, DISABLED_TTL);
        } catch (DataAccessException e) {
            log.warn("注销时写账号状态缓存失败，将由查库兜底 user_id={}", userId, e);
        }
    }

    private boolean activeInDb(long userId) {
        // selectById 会带上逻辑删除条件：被删的账号也当作不可用
        User user = userMapper.selectById(userId);
        return user != null && (user.getStatus() == null || user.getStatus() != User.STATUS_DISABLED);
    }

    private String key(long userId) {
        return KEY_PREFIX + userId;
    }
}
