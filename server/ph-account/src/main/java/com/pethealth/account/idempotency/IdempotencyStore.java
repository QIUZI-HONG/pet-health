package com.pethealth.account.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethealth.account.config.IdempotencyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 幂等键在 Redis 里的存取（ADR-0028 的存储半边）。
 *
 * <p>与 {@code IdempotencyFilter} 的分工：**过滤器只管 HTTP**（读不读头、怎么回放响应、
 * 给用户什么文案），**本类只管存储**（认领、读回、写入、释放、指纹、序列化）。
 * 拆开的理由是这两半的变化原因不同：改回放语义不该动 Lua 脚本，调 TTL 也不该动过滤器。
 *
 * <h2>归属令牌（owner）</h2>
 *
 * 认领（{@code SETNX}）本身是原子的，但**认领之后的写回与释放不是**。设想一个执行超过 TTL 的请求 A：
 * 它的 {@code in_flight} 记录到期后，请求 B 认领了同一个键并正常写完结果；此时 A 才收尾——
 * 若 A 盲写就会**覆盖 B 的结果**，若 A 遇到 5xx 盲删就会**删掉 B 已完成的结果**。
 * 两种都会让同一个键执行两次，正好破坏这个功能的全部承诺。
 *
 * <p>所以每次认领生成一个归属令牌，写入与删除都通过 Lua 脚本**比对令牌后才动手**：
 * 不是自己的记录就不动，A 的收尾因此变成无害的空操作。
 */
@Component
public class IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyStore.class);

    private static final String KEY_PREFIX = "ph:idem:";

    /** 单次可缓存的响应体上限。超过就不记录（释放键），免得为大响应白占内存。 */
    private static final int MAX_CACHED_BODY = 256 * 1024;

    /**
     * {@code in_flight} 标记的最长寿命。
     *
     * <p>**刻意短于配置里的 TTL**：配置那个值是「客户端可以安全重试多久」，而这个是「一次执行最多算多久」。
     * 用一个 10 分钟的配置值去标记在途，会让一个卡住/被杀掉的请求把同一个键的重试堵满 10 分钟。
     * 本仓的接口 P95 在秒级，AI 咨询最坏是一次 20 秒的读超时，60 秒已经很宽。
     */
    private static final Duration MAX_IN_FLIGHT = Duration.ofSeconds(60);

    private static final String STATE_IN_FLIGHT = "in_flight";
    private static final String STATE_DONE = "done";

    /** 值是 {@code owner|json}：归属令牌放在最前面，Lua 脚本靠它比对（令牌是 UUID，不含分隔符）。 */
    private static final char OWNER_SEPARATOR = '|';

    /** 只有归属匹配才写入。返回 1 表示写成功，0 表示记录已不属于本次认领（什么都不做）。 */
    private static final RedisScript<Long> SET_IF_OWNER = new DefaultRedisScript<>(
            "local v = redis.call('get', KEYS[1]) "
                    + "if not v then return 0 end "
                    + "if string.sub(v, 1, string.len(ARGV[1])) ~= ARGV[1] then return 0 end "
                    + "redis.call('set', KEYS[1], ARGV[2], 'PX', ARGV[3]) "
                    + "return 1", Long.class);

    /** 只有归属匹配才删除。用于释放（5xx / 抛异常）——不能删掉别人已经写好的结果。 */
    private static final RedisScript<Long> DELETE_IF_OWNER = new DefaultRedisScript<>(
            "local v = redis.call('get', KEYS[1]) "
                    + "if not v then return 0 end "
                    + "if string.sub(v, 1, string.len(ARGV[1])) ~= ARGV[1] then return 0 end "
                    + "return redis.call('del', KEYS[1])", Long.class);

    private final IdempotencyProperties properties;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public IdempotencyStore(IdempotencyProperties properties, StringRedisTemplate redis,
                            ObjectMapper objectMapper) {
        this.properties = properties;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /** 认领的结果：抢到了 / 别人正拿着 / Redis 不可用（放行，见 ADR-0028）。 */
    public enum Reservation { CLAIMED, BUSY, UNAVAILABLE }

    /**
     * 存在 Redis 里的那次请求的结果。
     *
     * @param state       {@code in_flight}（还有没跑完）或 {@code done}
     * @param fingerprint 请求指纹，用来发现同键不同体
     * @param status      首次的 HTTP 状态码（仅 done 有意义）
     * @param contentType 首次的响应内容类型（仅 done 有意义）
     * @param body        首次的响应体，base64（仅 done 有意义）
     */
    public record Stored(String state, String fingerprint, int status, String contentType, String body) {

        /** 上一次同键的请求还没跑完。 */
        public boolean inFlight() {
            return STATE_IN_FLIGHT.equals(state);
        }
    }

    /**
     * Redis 键 = 前缀 + 调用方 + 方法 + 路径 + 客户端给的键值（ADR-0028）。
     *
     * <p>{@code scope} 由调用方（过滤器）拼好：**带上「调用方」是必须的**——客户端自己生成的键
     * 很可能重复（比如「1」「2」），不加主体就会串到别人的响应上去。
     */
    public String keyFor(String scope, String clientKey) {
        return KEY_PREFIX + scope + ":" + clientKey;
    }

    /** 归属令牌：每次认领一个新值。 */
    public String newOwner() {
        return UUID.randomUUID().toString();
    }

    /**
     * 请求指纹：方法 + 路径 + 请求体的两次 SHA-256。
     *
     * <p>用来发现「同一个键换了请求体」——那种情况必须拒绝（见 {@code IdempotencyFilter} 的回放分支）。
     */
    public static String fingerprint(String identity, byte[] body) {
        return sha256(identity + "\n" + sha256(new String(body, StandardCharsets.UTF_8)));
    }

    /**
     * 认领键。在途标记的 TTL 取「配置 TTL 与 {@link #MAX_IN_FLIGHT} 的较小者」。
     */
    public Reservation reserve(String redisKey, String owner, String fingerprint) {
        Duration inFlightTtl = properties.ttl().compareTo(MAX_IN_FLIGHT) > 0
                ? MAX_IN_FLIGHT
                : properties.ttl();
        String value = owner + OWNER_SEPARATOR
                + serialize(new Stored(STATE_IN_FLIGHT, fingerprint, 0, "", ""));
        try {
            Boolean claimed = redis.opsForValue().setIfAbsent(redisKey, value, inFlightTtl);
            return Boolean.TRUE.equals(claimed) ? Reservation.CLAIMED : Reservation.BUSY;
        } catch (RuntimeException e) {
            log.warn("幂等键不可用，本次不做幂等：key={}", redisKey, e);
            return Reservation.UNAVAILABLE;
        }
    }

    /**
     * 读回已存在的记录。
     *
     * <p>返回空有三种原因——记录在「认领失败」与「读」之间到期了（TTL 边界，窗口极小）、
     * Redis 读失败、JSON 解析失败——**调用方一律按冲突处理**：既然认领失败过，
     * 说明有另一个同键请求存在过；此时执行还是拒绝，两个选择都可能错，
     * 而拒绝只让客户端多试一次，执行却可能重复一遍副作用（比如重复扣一次 AI 额度）。
     */
    public Optional<Stored> load(String redisKey) {
        String raw;
        try {
            raw = redis.opsForValue().get(redisKey);
        } catch (RuntimeException e) {
            log.warn("幂等记录读取失败，按冲突处理：key={}", redisKey, e);
            return Optional.empty();
        }
        if (raw == null) {
            log.warn("幂等记录在读取前消失（TTL 边界），按冲突处理：key={}", redisKey);
            return Optional.empty();
        }
        try {
            return Optional.of(parse(stripOwner(raw)));
        } catch (IOException | RuntimeException e) {
            log.warn("幂等记录解析失败，按冲突处理：key={}", redisKey, e);
            return Optional.empty();
        }
    }

    /**
     * 记录首次执行的结果，供后续同键请求回放。
     *
     * <p>两条「不记」的规则：**5xx 不持久化**（按 IETF 的 Idempotency-Key 草案，存下 5xx 会让
     * 一次瞬时故障在 TTL 窗口内「粘住」，客户端怎么重试都拿到同一个失败），
     * **超过 {@link #MAX_CACHED_BODY} 的响应不记**（大响应白占内存）。两者都改为释放键，
     * 让重试能真的重跑。
     */
    public void complete(String redisKey, String owner, String fingerprint,
                         int status, String contentType, byte[] body) {
        if (status >= 500) {
            release(redisKey, owner);
            return;
        }
        if (body.length > MAX_CACHED_BODY) {
            log.info("响应体过大（{} 字节 > {}），本次不做幂等记录：key={}",
                    body.length, MAX_CACHED_BODY, redisKey);
            release(redisKey, owner);
            return;
        }
        // 指纹沿用认领时那一份（不是重新算的）：重放时要用它判断「同键是否换了请求体」
        Stored stored = new Stored(STATE_DONE, fingerprint, status,
                contentType == null ? "" : contentType,
                Base64.getEncoder().encodeToString(body));
        String value = owner + OWNER_SEPARATOR + serialize(stored);
        try {
            Long written = redis.execute(SET_IF_OWNER, List.of(redisKey), owner, value,
                    Long.toString(properties.ttl().toMillis()));
            if (written == null || written == 0L) {
                // 记录已经不是我们的了（本次执行超过了在途 TTL，别人已认领并写完）：
                // 不能覆盖——覆盖会让同一个键留下别人的结果与本次的执行两条痕迹
                log.warn("本次认领已过期（执行超过 {}），结果不写入以免覆盖他人：key={}", MAX_IN_FLIGHT, redisKey);
            }
        } catch (RuntimeException e) {
            // 记不下来不影响这次响应已经发出；只是这一次的重试会重复执行
            log.warn("幂等记录写入失败，本次不幂等：key={}", redisKey, e);
        }
    }

    /** 释放键（归属匹配才删）。用于 5xx 与执行中抛异常：让客户端重试能真的重跑。 */
    public void release(String redisKey, String owner) {
        try {
            redis.execute(DELETE_IF_OWNER, List.of(redisKey), owner);
        } catch (RuntimeException e) {
            log.warn("幂等键释放失败（TTL 到期后自然消失）：key={}", redisKey, e);
        }
    }

    private static String stripOwner(String raw) {
        int separator = raw.indexOf(OWNER_SEPARATOR);
        return separator < 0 ? raw : raw.substring(separator + 1);
    }

    private Stored parse(String json) throws IOException {
        return objectMapper.readValue(json, Stored.class);
    }

    private String serialize(Stored stored) {
        try {
            return objectMapper.writeValueAsString(stored);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("幂等记录序列化失败", e);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256", e);
        }
    }
}
