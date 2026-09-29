package com.pethealth.boot.security;

import com.pethealth.account.web.IdempotencyFilter;
import com.pethealth.boot.support.IntegrationTestBase;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 幂等过滤器里两条**集成测试够不到**的分支（ADR-0028）。
 *
 * <p>为什么单独写在这一层：端到端测「5xx 不持久化」需要后端真的返回 500，
 * 而本仓的接口对任何合法输入都不会 5xx（这本身是好事）；测「在途」则需要让第一次请求
 * 停在半路——靠并发去撞是碰运气。这里直接驱动过滤器、用链子里的回调精确制造这两个状态，
 * 断言就变成确定的。
 *
 * <p>用的是容器里真实的 Redis：这三条规则都是**与 Redis 的交互语义**（SETNX 认领、TTL、
 * 键的删除），换成内存替身就测不到真正要测的东西。
 */
@DisplayName("幂等过滤器分支（ADR-0028）")
class IdempotencyFilterTest extends IntegrationTestBase {

    private static final String PATH = "/api/v1/app/pets";
    private static final String BODY = "{\"name\":\"豆豆\",\"species\":1}";

    @Autowired
    private IdempotencyFilter filter;

    private MockHttpServletRequest request(String key) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
        request.setRequestURI(PATH);
        request.setContentType("application/json");
        request.setContent(BODY.getBytes(StandardCharsets.UTF_8));
        if (key != null) {
            request.addHeader(IdempotencyFilter.HEADER, key);
        }
        return request;
    }

    @Test
    @DisplayName("首次执行返回 5xx：先真的认领过，然后释放（不留下记录）")
    void serverErrorReleasesTheKey() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicInteger keysDuringChain = new AtomicInteger(-1);

        filter.doFilter(request("key-5xx"), response, (req, res) -> {
            // 先证明过滤器**真的介入了**：只断言「最后没有键」是不够的——
            // 把 shouldNotFilter 改成恒 true 那条断言照样通过，但它证明不了「释放」这件事
            keysDuringChain.set(redis.keys("ph:idem:*").size());
            ((HttpServletResponse) res).setStatus(503);
        });

        assertThat(keysDuringChain.get()).as("执行期间必须存在在途记录").isEqualTo(1);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(redis.keys("ph:idem:*"))
                .as("5xx 不持久化——否则一次瞬时故障会在 TTL 内粘住，重试永远拿到同一个失败")
                .isEmpty();
    }

    @Test
    @DisplayName("执行过程中抛异常：释放键，且把异常原样抛给容器去渲染 5xx")
    void exceptionReleasesTheKeyAndPropagates() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(request("key-boom"), response,
                (req, res) -> {
                    throw new IllegalStateException("后端炸了");
                }))
                .as("异常必须抛出去——过滤器不该把它吞成 200")
                .isInstanceOf(IllegalStateException.class);
        assertThat(redis.keys("ph:idem:*"))
                .as("没跑完就不该留下记录，否则重试会一直拿到 40900").isEmpty();
    }

    @Test
    @DisplayName("响应体过大：放弃幂等记录，不把大响应缓进内存")
    void oversizedResponseIsNotCached() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("key-big"), response, (req, res) -> {
            ((HttpServletResponse) res).setStatus(200);
            ((HttpServletResponse) res).getWriter().write("a".repeat(300 * 1024));
        });

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(redis.keys("ph:idem:*")).as("超过上限就不记").isEmpty();
    }

    @Test
    @DisplayName("2xx 会留下记录，且带 TTL（不能留一个永不过期的键）")
    void successIsRememberedWithTtl() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("key-2xx"), response,
                (req, res) -> ((HttpServletResponse) res).setStatus(200));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(redis.keys("ph:idem:*")).hasSize(1);
        assertThat(redis.getExpire(redis.keys("ph:idem:*").iterator().next()))
                .as("必须带 TTL，否则键会永久占用").isGreaterThan(0);
    }

    @Test
    @DisplayName("在途：上一次还没跑完，同键的第二次得到 40900 且不被执行")
    void concurrentSameKeyIsRejectedWhileInFlight() throws Exception {
        AtomicInteger secondStatus = new AtomicInteger();
        AtomicReference<String> secondBody = new AtomicReference<>();

        // 第一次请求在链子里「还没跑完」——此时发起第二次同键请求
        filter.doFilter(request("key-inflight"), new MockHttpServletResponse(), (req, res) -> {
            MockHttpServletResponse second = new MockHttpServletResponse();
            filter.doFilter(request("key-inflight"), second,
                    (r2, s2) -> {
                        throw new AssertionError("在途时第二次不该被执行");
                    });
            secondStatus.set(second.getStatus());
            secondBody.set(second.getContentAsString());
            ((HttpServletResponse) res).setStatus(200);
        });

        assertThat(secondStatus.get()).as("HTTP 409").isEqualTo(409);
        assertThat(secondBody.get()).contains("40900").contains("处理中");
    }
}
