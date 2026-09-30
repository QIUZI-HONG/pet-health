package com.pethealth.account.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethealth.account.config.IdempotencyProperties;
import com.pethealth.account.idempotency.IdempotencyStore;
import com.pethealth.account.idempotency.IdempotencyStore.Reservation;
import com.pethealth.account.metrics.SecurityMetrics;
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
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;

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
 * <p>Redis 侧的认领/写回/释放与归属令牌机制在 {@link IdempotencyStore}（拆开是因为
 * 改回放语义不该动 Lua 脚本，调 TTL 也不该动过滤器）；本类只决定**什么时候**用它们。
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

    /** 客户端给的键的长度上限。超了就不做幂等，免得一个几 KB 的值把 Redis 键撑大。 */
    private static final int MAX_KEY_LENGTH = 200;

    private final IdempotencyProperties properties;
    private final IdempotencyStore store;
    private final ObjectMapper objectMapper;
    private final SecurityMetrics metrics;

    public IdempotencyFilter(IdempotencyProperties properties, IdempotencyStore store,
                             ObjectMapper objectMapper, SecurityMetrics metrics) {
        this.properties = properties;
        this.store = store;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
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
        String fingerprint = IdempotencyStore.fingerprint(identity, body);
        String redisKey = redisKey(request);
        String owner = store.newOwner();

        Reservation reservation = store.reserve(redisKey, owner, fingerprint);
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
            store.release(redisKey, owner);
            throw e;
        }
        store.complete(redisKey, owner, fingerprint, wrapper.getStatus(),
                wrapper.getContentType(), wrapper.getContentAsByteArray());
        // 必须调：wrapper 把响应体缓存在自己身上，不 copy 回去客户端会拿到空响应
        wrapper.copyBodyToResponse();
    }

    // ---------------------------------------------------------------- 回放

    /**
     * 已有同键记录时的处理。
     *
     * <p>指纹不一致必须拒掉：否则客户端拿旧键发新请求，会拿回**上一次的答案**——
     * 「看起来成功、内容却不是这次请求的」，比报错危险得多。
     */
    private void returnExisting(String redisKey, String fingerprint, HttpServletResponse response)
            throws IOException {
        Optional<IdempotencyStore.Stored> found = store.load(redisKey);
        if (found.isEmpty()) {
            // 「记录消失 / 读不到」的取舍（保守地按冲突处理）写在 IdempotencyStore#load 的注释里
            FilterErrors.write(response, objectMapper, ErrorCode.CONFLICT, "请求正在处理中，请稍后重试");
            return;
        }
        IdempotencyStore.Stored stored = found.get();

        if (!fingerprint.equals(stored.fingerprint())) {
            // 这类是客户端用错了键（拿旧键发新请求）。**刻意不释放键**：释放等于允许这次不同的请求
            // 去执行，而它本来就该失败——用户要换一个键
            metrics.idempotencyConflict("fingerprint_mismatch");
            FilterErrors.write(response, objectMapper, ErrorCode.PARAM_INVALID,
                    "同一个幂等键不能用于不同的请求体，请换一个键");
            return;
        }
        if (stored.inFlight()) {
            metrics.idempotencyConflict("in_flight");
            FilterErrors.write(response, objectMapper, ErrorCode.CONFLICT,
                    "上一次同样的请求还在处理中，请稍后重试（或换一个幂等键）");
            return;
        }
        // 重放：这次请求没有真的执行。持续增长通常意味着客户端在重试风暴里
        metrics.idempotentReplayed();
        replay(stored, response);
    }

    /** 回放首次的响应：状态码、内容类型与响应体都照原样。 */
    private void replay(IdempotencyStore.Stored stored, HttpServletResponse response) throws IOException {
        response.setStatus(stored.status());
        if (stored.contentType() != null && !stored.contentType().isBlank()) {
            response.setContentType(stored.contentType());
        }
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        byte[] body = Base64.getDecoder().decode(stored.body());
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    // ---------------------------------------------------------------- 键与工具

    /**
     * Redis 键 = 调用方 + 方法 + 路径 + 客户端给的键值（ADR-0028）。
     *
     * <p>带上「调用方」是必须的：客户端自己生成的键很可能重复（比如「1」「2」），
     * 不加主体就会串到别人的响应上去。
     */
    private String redisKey(HttpServletRequest request) {
        String scope = CallerKey.of(request) + ":" + request.getMethod()
                + ":" + RequestPaths.withinApplication(request);
        return store.keyFor(scope, request.getHeader(HEADER));
    }

    private static boolean isJsonBody(HttpServletRequest request) {
        String contentType = request.getContentType();
        return contentType != null && contentType.toLowerCase().startsWith(MediaType.APPLICATION_JSON_VALUE);
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
