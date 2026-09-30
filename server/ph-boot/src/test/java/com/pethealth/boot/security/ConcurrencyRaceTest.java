package com.pethealth.boot.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethealth.api.app.CheckInItemRequest;
import com.pethealth.api.app.CheckInSubmitRequest;
import com.pethealth.api.app.FilePresignRequest;
import com.pethealth.boot.support.ApiClient;
import com.pethealth.boot.support.IntegrationTestBase;
import com.pethealth.common.time.AppTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发竞态（2026-09-28 深测轮）。
 *
 * <p>已有用例覆盖的是**顺序**意义上的幂等：重复注册、重复撤销、凭证二次落定，都是一前一后地发。
 * 但「同一瞬间两个请求」是另一类问题——唯一键冲突、Redis 命令的原子性、事务边界。
 * 本项目已经因为这一类修过三条（撤销后重填 D2、提醒惰性补算 D9、评分并发 D19），
 * 所以这里把还没打过的四条路径补齐。
 *
 * <p>判据是统一的：**可以失败，但不能以 50000 失败**。用户看到「服务器内部错误」时，
 * 真实原因往往是「你已经注册过了」这种完全可以说清楚的事（D2/D19 的现场就是如此）。
 *
 * <p>关于「同一个 refresh token 并发」：重放检测（ADR-0012）会把整个会话族吊销，
 * 所以并发刷新时**赢家刚换到的令牌也可能被连坐**——这是刻意的安全取舍，不是缺陷。
 * 用例只钉住三条确定性结论（至多一个成功、其余 40100、没有 50000），
 * 不钉「新令牌是否被连坐」：那取决于两个请求的时序，钉住只会写出随机红的用例。
 */
@DisplayName("并发竞态（唯一键 / Redis 原子性 / 事务边界）")
class ConcurrencyRaceTest extends IntegrationTestBase {

    private static final String PHONE = "13900009001";

    @Autowired
    private ObjectMapper objectMapper;

    private ApiClient api;

    @BeforeEach
    void setUpClient() {
        api = new ApiClient(rest, objectMapper);
    }

    // ------------------------------------------------------------ 注册

    @Test
    @DisplayName("同一手机号并发注册：恰好一个成功，其余 40900，没有 50000")
    void concurrentRegistrationCreatesExactlyOneAccount() {
        List<ApiClient.ApiCall> results = race(8, index -> api.register(PHONE));

        assertThat(results.stream().filter(call -> call.status() == 200).count())
                .as("只有一个请求能拿到账号：" + summary(results))
                .isEqualTo(1);
        assertThat(results).allSatisfy(call -> assertThat(call.code())
                .as("失败的那种必须是「该手机号已注册」（40900），不能是内部错误：" + call.body())
                .isIn(0, 40900));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `user`", Integer.class))
                .as("数据库里只该有一个账号")
                .isEqualTo(1);
    }

    // ------------------------------------------------------------ 令牌刷新

    @Test
    @DisplayName("同一个 refresh token 并发使用：至多一个成功，其余 40100，没有 50000")
    void concurrentRefreshIssuesAtMostOneTokenPair() {
        String refreshToken = api.registerAndGetRefreshToken(PHONE);

        List<ApiClient.ApiCall> results = race(4,
                index -> api.post("/api/v1/app/auth/refresh", Map.of("refresh_token", refreshToken)));

        assertThat(results.stream().filter(call -> call.status() == 200).count())
                .as("一次性令牌只能换出一对：" + summary(results))
                .isLessThanOrEqualTo(1);
        assertThat(results).allSatisfy(call -> assertThat(call.code())
                .as("其余请求按「令牌无效 / 已被用过」处理：40100 是普通失效，"
                        + "40101 是重放检测（登录凭证被重复使用，整个会话族已吊销）：" + call.body())
                .isIn(0, 40100, 40101));
    }

    // ------------------------------------------------------------ 上传凭证

    @Test
    @DisplayName("同一上传凭证并发落定：恰好一个 204，其余按无效凭证处理，没有 50000")
    void concurrentSettleOfOnePresignStoresOneFile() {
        String token = api.registerAndGetAccessToken(PHONE);
        long petId = api.createPet(token, "豆豆");

        ApiClient.ApiCall presign = api.post("/api/v1/app/files/presign",
                new FilePresignRequest("checkin", petId,
                        List.of(new FilePresignRequest.Item("image/png", null, null))), token);
        assertThat(presign.code()).as(presign.body().toPrettyString()).isZero();
        long fileId = presign.data().get(0).path("file_id").asLong();
        String uploadUrl = presign.data().get(0).path("upload_url").asText();
        byte[] png = png();

        List<ApiClient.ApiCall> results = race(4, index -> api.putBinary(uploadUrl, png));

        assertThat(results.stream().filter(call -> call.status() == 204).count())
                .as("凭证只能用一次（原先这里是读-判-写，实测 4/4 全 204）：" + summary(results))
                .isEqualTo(1);
        assertThat(results.stream().filter(call -> call.status() != 204))
                .as("抢输的一方与顺序重复上传得到同一个答复（40900），客户端不需要区分这两种情况")
                .allSatisfy(call -> assertThat(call.code()).isEqualTo(40900));

        // 元数据只有一份，且字节数与上传的一致（没有写出两份文件）
        JsonNode view = api.get("/api/v1/app/files/" + fileId, token).data();
        assertThat(view.path("size_bytes").asLong()).isEqualTo(png.length);
        // 缩略图也只该有一行：抢输的请求如果也走到落定之后，会各自插一行缩略图（实测 4 行）
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM file_object WHERE original_id = ?", Integer.class, fileId))
                .as("每张原图只该生成一张缩略图")
                .isEqualTo(1);
    }

    // ------------------------------------------------------------ 打卡

    @Test
    @DisplayName("同一分项并发提交：全部 200，且该槽只有一行活记录（原子 upsert）")
    void concurrentSubmitsToOneCategoryKeepASingleRow() {
        String token = api.registerAndGetAccessToken(PHONE);
        long petId = api.createPet(token, "豆豆");

        List<ApiClient.ApiCall> results = race(6, index -> submit(token, petId, 2, "normal"));

        assertThat(results).allSatisfy(call -> assertThat(call.status())
                .as("同一个槽的并发写应当是「更新或复活」，不该撞唯一键：" + call.body())
                .isEqualTo(200));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM archive_record WHERE pet_id = ? AND category = 2 AND is_deleted = 0",
                Integer.class, petId))
                .as("一个槽只该有一行活记录")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("撤销与重新填同一天同一项并发：允许任一方先赢，但不许 50000")
    void concurrentUndoAndResubmitNeverFailWithInternalError() {
        String token = api.registerAndGetAccessToken(PHONE);
        long petId = api.createPet(token, "豆豆");
        assertThat(submit(token, petId, 6, "normal").code()).isZero();

        // 两个线程对着同一个槽：一个撤销、一个重填。这正是「填错 → 撤销 → 重新填」被快速连点的形状，
        // 也是 D2 的现场（当时报的是唯一键冲突 50000）。
        String undoUrl = "/api/v1/app/pets/" + petId + "/check-ins/item?date=" + today() + "&category=6";
        List<ApiClient.ApiCall> results = race(2, index -> index == 0
                ? api.delete(undoUrl, token)
                : submit(token, petId, 6, "normal"));

        assertThat(results).allSatisfy(call -> assertThat(call.code())
                .as("无论谁先执行，都不该是内部错误：" + call.body())
                .isNotEqualTo(50000));
        // 收尾状态必须自洽：该槽最多一行活记录，当日评分行也最多一条
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM archive_record WHERE pet_id = ? AND category = 6 AND is_deleted = 0",
                Integer.class, petId))
                .isLessThanOrEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM health_score WHERE pet_id = ? AND calc_date = ?",
                Integer.class, petId, today()))
                .isLessThanOrEqualTo(1);
    }

    // ------------------------------------------------------------ 辅助

    /** 让所有线程在闸门放开的那一瞬间同时发请求——竞态用例只有在真并发下才有意义。 */
    private <T> List<T> race(int threads, IntFunction<T> action) {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int index = i;
                Callable<T> task = () -> {
                    start.await();
                    return action.apply(index);
                };
                futures.add(pool.submit(task));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } catch (Exception e) {
            throw new IllegalStateException("并发调用本身失败（不是被测代码的问题）", e);
        } finally {
            pool.shutdownNow();
        }
    }

    private ApiClient.ApiCall submit(String token, long petId, int category, String value) {
        return api.post("/api/v1/app/pets/" + petId + "/check-ins",
                new CheckInSubmitRequest(today(), List.of(new CheckInItemRequest(category, false, value, null))),
                token);
    }

    private static String today() {
        return AppTime.today().toString();
    }

    private static String summary(List<ApiClient.ApiCall> calls) {
        return calls.stream().map(call -> call.status() + "/" + call.code()).toList().toString();
    }

    /** 一张真的 PNG（假文件过不了魔数与解码，会让「魔数对了但内容坏了」这条被漏测）。 */
    private static byte[] png() {
        try {
            var image = new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, 0x00FF00);
            var out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("生成测试图片失败", e);
        }
    }
}
