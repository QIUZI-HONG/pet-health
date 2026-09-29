package com.pethealth.account.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethealth.account.config.IdempotencyProperties;
import com.pethealth.common.error.ErrorCode;
import com.pethealth.common.web.CallerKey;
import com.pethealth.common.web.FilterErrors;
import com.pethealth.common.web.RequestPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 写接口幂等（ADR-0028）：客户端带 {@code Idempotency-Key} 时，同一个键的重复提交
 * **只执行一次**，重放返回第一次的响应。
 *
 * <p>四条刻意的边界：
 *
 * <ol>
 *   <li><b>可选</b>：不带这个头的请求行为**完全不变**。所以要先判断头是否存在，
 *       而不是给所有写请求都套一层——那会改掉所有现有调用方的语义。
 *   <li><b>只处理 JSON 请求体</b>（{@code Content-Type: application/json}）。幂等需要先把请求体
 *       读进内存算指纹，而上传接口是 {@code application/octet-stream} 的 10MB 字节流——
 *       把那种请求缓冲进堆里是拿内存换一个不需要的功能。上传的重复提交由上传凭证管（ADR-0020）。
 *   <li><b>5xx 不持久化</b>：按 IETF 的 Idempotency-Key 草案，服务端不该存 5xx——
 *       那会让一次瞬时故障在 TTL 窗口内「粘住」，客户端怎么重试都拿到同一个失败。
 *       所以 5xx（含过程中抛异常）都**释放键**，让重试能真的重跑；2xx 与 4xx 则回放
 *       （4xx 是确定性结论，重跑也是同样结果；而 AI 咨询这类**可能已经花过钱**的写操作，
 *       宁可回放也不重跑）。
 *   <li><b>写入与释放都带归属校验</b>（下详）。没有它，「只执行一次」在超时场景下会破。
 * </ol>
 *
 * <h2>为什么要「归属令牌」</h2>
 *
 * 认领（{@code SETNX}）本身是原子的，但**认领之后的写回与释放不是**。设想一个执行超过 TTL 的请求 A：
 * 它的 {@code in_flight} 记录到期后，请求 B 认领了同一个键并正常写完结果；此时 A 才收尾——
 * 若 A 盲写就会**覆盖 B 的结果**，若 A 遇到 5xx 盲删就会**删掉 B 已完成的结果**。
 * 两种都会让同一个键执行两次，正好破坏这个功能的全部承诺。
 *
 * 所以每次认领生成一个归属令牌（owner），值与写入/删除都通过 Lua 脚本**比对令牌后才动手**：
 * 不是自己的记录就不动。A 的收尾因此变成无害的空操作。
 *
 * <p>另外 {@code in_flight} 标记的 TTL 被**单独压短**（{@link #MAX_IN_FLIGHT}），不用配置里的
 * 那 10 分钟：一个卡住的请求不该让同一个键的重试整整 10 分钟都拿到 40900。
 *
 * <p>排在限流之后（{@code HIGHEST_PRECEDENCE + 30}）：先让限流挡掉刷键的流量，再谈幂等；
 * 两者都排在鉴权之后，因为键要用到「是谁发的」。
 *
 * <p><b>回放的保真度</b>：还原来的是状态码、内容类型与响应体。处理器在第一次执行时设置的响应头
 * **不会还原**（本仓的处理器都不设响应头，所以现在没有影响）。更早的过滤器设过的头是有的，
 * 因为回放写的就是同一个响应对象：{@code TraceIdFilter} 的 {@code X-Request-Id}、
 * 限流器的 {@code Retry-After} 都在。由此有一处**刻意的不一致**：重放响应体里带的是
 * **第一次**的 {@code request_id}，而响应头带的是本次的——这是「回放首次结果」的必然含义
 * （那才是同一次逻辑请求的答案），排查时以响应头的 traceId 为准。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);

    /** 幂等只对写方法有意义。 */
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    public static final String HEADER = "Idempotency-Key";

    private static final String KEY_PREFIX = "ph:idem:";

    /** 客户端给的键的长度上限。超了就不做幂等，免得一个几 KB 的值把 Redis 键撑大。 */
    private static final int MAX_KEY_LENGTH = 200;

    /** 单次可缓存的响应体上限。超过就不记录（放行），免得为大响应白占内存。 */
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

    public IdempotencyFilter(IdempotencyProperties properties, StringRedisTemplate redis,
                             ObjectMapper objectMapper) {
        this.properties = properties;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.isEnabled() || !WRITE_METHODS.contains(request.getMethod())) {
            return true;
        }
        String key = request.getHeader(HEADER);
        if (key == null || key.isBlank()) {
            return true;
        }
        if (key.length() > MAX_KEY_LENGTH) {
            // 不静默跳过：调用方以为自己是幂等的，实际不是。留一条日志（这种超长键基本是程序 bug）
            log.warn("Idempotency-Key 超过 {} 字符，本次不做幂等：method={} path={}",
                    MAX_KEY_LENGTH, request.getMethod(), request.getRequestURI());
            return true;
        }
        return !isJsonBody(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        byte[] body = request.getInputStream().readAllBytes();
        String identity = request.getMethod() + "\n" + RequestPaths.withinApplication(request);
        String fingerprint = sha256(identity + "\n" + sha256(new String(body, StandardCharsets.UTF_8)));
        String redisKey = redisKey(request);
        String owner = UUID.randomUUID().toString();

        Reservation reservation = reserve(redisKey, owner, fingerprint);
        if (reservation == Reservation.BUSY) {
            returnExisting(redisKey, fingerprint, response);
            return;
        }
        if (reservation == Reservation.UNAVAILABLE) {
            // Redis 不可用：放行。取舍与理由见 ADR-0028「幂等键在 Redis 不可用时怎么办」一节
            chain.doFilter(cachedBody(request, body), response);
            return;
        }

        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(cachedBody(request, body), wrapper);
        } catch (ServletException | IOException | RuntimeException e) {
            // 没走完：释放键（归属匹配才删），让客户端重试能真的重跑。
            // **不调 copyBodyToResponse()**：那会把「尚无内容的 200」提交出去，
            // 把容器的错误页（5xx）挡在门外——异常就该让容器去渲染
            release(redisKey, owner);
            throw e;
        }
        remember(redisKey, owner, fingerprint, wrapper);
        // 必须调：wrapper 把响应体缓存在自己身上，不 copy 回去客户端会拿到空响应
        wrapper.copyBodyToResponse();
    }

    // ---------------------------------------------------------------- 认领与回放

    private enum Reservation { CLAIMED, BUSY, UNAVAILABLE }

    private Reservation reserve(String redisKey, String owner, String fingerprint) {
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
     * 已有同键记录时的处理。
     *
     * <p>指纹不一致必须拒掉：否则客户端拿旧键发新请求，会拿回**上一次的答案**——
     * 「看起来成功、内容却不是这次请求的」，比报错危险得多。
     */
    private void returnExisting(String redisKey, String fingerprint, HttpServletResponse response)
            throws IOException {
        Stored stored;
        try {
            String raw = redis.opsForValue().get(redisKey);
            if (raw == null) {
                // 记录在「认领失败」与「读」之间到期了（TTL 边界，窗口极小）。
                // **保守地按冲突处理**：既然认领失败过，说明有另一个同键请求存在过；
                // 此时执行还是拒绝，两个选择都可能错——拒绝只让客户端多试一次，
                // 执行却可能重复一遍副作用（比如重复扣一次 AI 额度）。所以选拒绝
                log.warn("幂等记录在读取前消失（TTL 边界），按冲突处理：key={}", redisKey);
                FilterErrors.write(response, objectMapper, ErrorCode.CONFLICT, "请求正在处理中，请稍后重试");
                return;
            }
            stored = parse(stripOwner(raw));
        } catch (RuntimeException | IOException e) {
            log.warn("幂等记录读取/解析失败，按冲突处理：key={}", redisKey, e);
            FilterErrors.write(response, objectMapper, ErrorCode.CONFLICT, "请求正在处理中，请稍后重试");
            return;
        }

        if (!fingerprint.equals(stored.fingerprint())) {
            // 这类是客户端用错了键（拿旧键发新请求）。**刻意不释放键**：释放等于允许这次不同的请求
            // 去执行，而它本来就该失败——用户要换一个键
            FilterErrors.write(response, objectMapper, ErrorCode.PARAM_INVALID,
                    "同一个幂等键不能用于不同的请求体，请换一个键");
            return;
        }
        if (STATE_IN_FLIGHT.equals(stored.state())) {
            FilterErrors.write(response, objectMapper, ErrorCode.CONFLICT,
                    "上一次同样的请求还在处理中，请稍后重试（或换一个幂等键）");
            return;
        }
        replay(stored, response);
    }

    /** 回放首次的响应：状态码、内容类型与响应体都照原样。 */
    private void replay(Stored stored, HttpServletResponse response) throws IOException {
        response.setStatus(stored.status());
        if (stored.contentType() != null && !stored.contentType().isBlank()) {
            response.setContentType(stored.contentType());
        }
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        byte[] body = Base64.getDecoder().decode(stored.body());
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    private void remember(String redisKey, String owner, String fingerprint,
                          ContentCachingResponseWrapper wrapper) {
        int status = wrapper.getStatus();
        if (status >= 500) {
            // 5xx 不持久化：否则一次瞬时故障会在 TTL 内粘住（见类注释第 3 条）
            release(redisKey, owner);
            return;
        }
        byte[] body = wrapper.getContentAsByteArray();
        if (body.length > MAX_CACHED_BODY) {
            log.info("响应体过大（{} 字节 > {}），本次不做幂等记录：key={}",
                    body.length, MAX_CACHED_BODY, redisKey);
            release(redisKey, owner);
            return;
        }
        Stored stored = new Stored(STATE_DONE, fingerprint, status,
                wrapper.getContentType() == null ? "" : wrapper.getContentType(),
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

    private void release(String redisKey, String owner) {
        try {
            redis.execute(DELETE_IF_OWNER, List.of(redisKey), owner);
        } catch (RuntimeException e) {
            log.warn("幂等键释放失败（TTL 到期后自然消失）：key={}", redisKey, e);
        }
    }

    // ---------------------------------------------------------------- 键与工具

    /**
     * Redis 键 = 调用方 + 方法 + 路径 + 客户端给的键值（ADR-0028）。
     *
     * <p>带上「调用方」是必须的：客户端自己生成的键很可能重复（比如「1」「2」），
     * 不加主体就会串到别人的响应上去。
     */
    private String redisKey(HttpServletRequest request) {
        return KEY_PREFIX + CallerKey.of(request) + ":" + request.getMethod()
                + ":" + RequestPaths.withinApplication(request) + ":" + request.getHeader(HEADER);
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

    private static boolean isJsonBody(HttpServletRequest request) {
        String contentType = request.getContentType();
        return contentType != null && contentType.toLowerCase().startsWith(MediaType.APPLICATION_JSON_VALUE);
    }

    /**
     * 存在 Redis 里的那次请求的结果。
     *
     * @param state       {@code in_flight}（还有没跑完）或 {@code done}
     * @param fingerprint 请求指纹，用来发现同键不同体
     * @param status      首次的 HTTP 状态码（仅 done 有意义）
     * @param contentType 首次的响应内容类型（仅 done 有意义）
     * @param body        首次的响应体，base64（仅 done 有意义）
     */
    private record Stored(String state, String fingerprint, int status, String contentType, String body) {
    }

    /** 把已经读过一遍的请求体再交回给下游：幂等需要先读一次算指纹，读流是一次性的。 */
    private static HttpServletRequest cachedBody(HttpServletRequest request, byte[] body) {
        return new HttpServletRequestWrapper(request) {
            @Override
            public ServletInputStream getInputStream() {
                ByteArrayInputStream source = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override
                    public boolean isFinished() {
                        return source.available() == 0;
                    }

                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    @Override
                    public void setReadListener(ReadListener readListener) {
                        throw new UnsupportedOperationException("幂等过滤器不参与异步读取");
                    }

                    @Override
                    public int read() {
                        return source.read();
                    }
                };
            }

            @Override
            public BufferedReader getReader() {
                return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
            }
        };
    }
}
