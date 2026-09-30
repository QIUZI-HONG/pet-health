package com.pethealth.boot.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethealth.account.auth.JwtService;
import com.pethealth.api.catalog.CatalogItemProposalRequest;
import com.pethealth.api.provider.OnboardingApplicationRequest;
import com.pethealth.api.provider.ProviderQualificationRequest;
import com.pethealth.boot.support.ApiClient;
import com.pethealth.boot.support.IntegrationTestBase;
import com.pethealth.boot.support.TestUploads;
import com.pethealth.common.security.LoginDomain;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 服务者侧与运营侧集成测试的公共基座：签发**后台登录域的令牌**、清库、造一个已入驻的服务者。
 *
 * <p><b>为什么测试要自己签令牌</b>：服务者后台与运营后台的登录入口还没实现——本切片只做
 * 业务侧（#104 / #105），后台账号与角色体系是另一个切片（ADR-0035 的「需要协调」）。
 * 于是测试直接调 {@link JwtService} 为「已注册账号的 id」签发 {@code provider} / {@code admin} 域的
 * 令牌：鉴权链路（登录域校验、账号可用性）与线上完全一致，**只有登录入口是绕过的**。
 * 等后台登录落地时，这里换成一次登录调用即可，用例本身不用改。
 *
 * <p>清理：{@code IntegrationTestBase} 不认识本切片的表（它是共享基类，不在本切片的改动范围里），
 * 所以这里补上——顺序是先子后主。目录的**种子数据不动**（那是迁移插的，清了整个切片就没内容了），
 * 目录测试自己造的 {@code TEST*} 分类与项目在这里一并清掉。
 */
public abstract class ProviderApiTestSupport extends IntegrationTestBase {

    /** 手机号序号：同一个用例里的多个账号不能撞号（唯一约束在 phone_hash 上）。 */
    private static final AtomicInteger PHONE_SEQ = new AtomicInteger(0);

    protected ApiClient api;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUpApi() {
        api = new ApiClient(rest, objectMapper);
        cleanSliceData();
    }

    private void cleanSliceData() {
        jdbc.execute("DELETE FROM `provider_service`");
        jdbc.execute("DELETE FROM `catalog_item_proposal`");
        jdbc.execute("DELETE FROM `provider_review_log`");
        jdbc.execute("DELETE FROM `provider_onboarding_application`");
        jdbc.execute("DELETE FROM `provider_qualification`");
        jdbc.execute("DELETE FROM `provider_user`");
        jdbc.execute("DELETE FROM `provider`");
        // 目录测试造的行（前缀 TEST 分类 / TX 项目）——种子目录不动
        jdbc.execute("DELETE FROM `service_item` WHERE `category_code` LIKE 'TEST%'");
        jdbc.execute("DELETE FROM `service_category` WHERE `code` LIKE 'TEST%'");
    }

    // ---------------------------------------------------------------- 身份

    /** 一个唯一的测试手机号（11 位）。 */
    protected String nextPhone() {
        int seq = PHONE_SEQ.incrementAndGet() % 100_000_000;
        return "139" + String.format("%08d", (int) ((System.nanoTime() / 1000 + seq) % 100_000_000));
    }

    /** 注册一个账号并返回它的 id（后台账号体系未落地前，后台令牌的主体就是它）。 */
    protected long registerAccount() {
        String phone = nextPhone();
        ApiClient.ApiCall call = api.register(phone);
        if (call.code() != 0) {
            throw new AssertionError("注册应当成功，实际：" + call.body());
        }
        return call.data().path("user").path("id").asLong();
    }

    /** 服务者后台令牌（主体是刚才注册的那个账号）。 */
    protected String providerToken() {
        return jwtService.issue(registerAccount(), LoginDomain.PROVIDER).token();
    }

    /** 服务者后台令牌，同时返回它对应的账号 id（要断言「申请人 = 管理员」时用）。 */
    protected ProviderActor providerActor() {
        long userId = registerAccount();
        return new ProviderActor(userId, jwtService.issue(userId, LoginDomain.PROVIDER).token());
    }

    /** 运营后台令牌。 */
    protected String adminToken() {
        return jwtService.issue(registerAccount(), LoginDomain.ADMIN).token();
    }

    /** 服务者身份：账号 id + 它的后台令牌。 */
    public record ProviderActor(long userId, String token) {
    }

    // ---------------------------------------------------------------- 造数据

    /**
     * 上传一张资质材料图（`biz_type=qualification`），返回 `file_id`。
     *
     * <p>材料**必带图**（ADR-0053），所以「造一份材料」现在必须先真的传一张图：
     * 只给证件号的请求在接口层就会被 40001 拒（那是刻意的，反面用例见
     * {@code QualificationImageTest}）。实现见 {@link TestUploads}——本方法是本切片里的顺手入口。
     */
    protected long uploadQualificationImage(String providerToken) {
        return TestUploads.qualificationImage(api, providerToken);
    }

    /**
     * 最小可用的入驻申请请求体。
     *
     * <p>{@code validUntil} 传 null 表示「长期有效」——上架门禁只认「有没有一份没过期的材料」，
     * 所以绝大多数用例用 null 才不会无意间踩到资质过期这条规则。
     *
     * <p><b>要令牌是因为材料必须带图</b>（ADR-0053）：这个方法会先真传一张资质图再组装请求体。
     */
    protected OnboardingApplicationRequest application(String providerToken, String name, String contactPhone,
                                                      String licenseNo, java.time.LocalDate validUntil) {
        return application(providerToken, name, contactPhone, licenseNo, validUntil, 1);
    }

    /**
     * 同上，但指定服务者分类（1 医院 / 2 洗护 / …）。
     *
     * <p>C 端浏览要按分类筛选，用例需要两个不同分类的门店——分类是 `provider.type`，
     * 只在入驻申请里能写，所以参数加在这一层。
     */
    protected OnboardingApplicationRequest application(String providerToken, String name, String contactPhone,
                                                      String licenseNo, java.time.LocalDate validUntil, int type) {
        return new OnboardingApplicationRequest(
                name, type, 1, null, "测试门店", "上海市徐汇区测试路 1 号", "121.4", "31.2",
                contactPhone, "张三",
                List.of(new ProviderQualificationRequest(1, "营业执照", licenseNo,
                        uploadQualificationImage(providerToken), null, validUntil)));
    }

    /** 提交入驻申请（用调用方的令牌）。返回响应，由用例自己断言。 */
    protected ApiClient.ApiCall submitApplication(String providerToken, String name, String contactPhone,
                                                  String licenseNo, java.time.LocalDate validUntil) {
        return api.post("/api/v1/provider/onboarding/applications",
                application(providerToken, name, contactPhone, licenseNo, validUntil), providerToken);
    }

    /**
     * 造一个「已审核通过、材料长期有效」的服务者，返回其 id 与管理员令牌。
     *
     * @param licenseNo 营业执照号（唯一性校验认它）
     */
    protected ApprovedProvider createApprovedProvider(String licenseNo) {
        return createApprovedProvider(licenseNo, null);
    }

    /**
     * 同上，但指定材料的到期日。
     *
     * <p>传一个过去的日期就得到「审核通过、但资质已过期」的店——那是 C 端不可浏览的四种情况之一
     * （ADR-0035 决定 6/7：资质全过期 = 已被自动下架全部服务项）。
     */
    protected ApprovedProvider createApprovedProvider(String licenseNo, java.time.LocalDate validUntil) {
        ProviderActor actor = providerActor();
        ApiClient.ApiCall submitted = submitApplication(actor.token(), "测试动物医院", "13800001111",
                licenseNo, validUntil);
        assertCodeOk(submitted, "提交入驻申请");
        long providerId = submitted.data().path("provider").path("id").asLong();

        ApiClient.ApiCall approved = api.post("/api/v1/admin/provider-applications/"
                        + submitted.data().path("id").asLong() + "/approve",
                Map.of("remark", "材料齐全"), adminToken());
        assertCodeOk(approved, "审核通过");
        return new ApprovedProvider(providerId, actor.token());
    }

    /** 已入驻的服务者。 */
    public record ApprovedProvider(long providerId, String token) {
    }

    /** 断言业务码为 0（成功），失败时把整个响应打出来，免得只看一行 message 猜原因。 */
    protected static void assertCodeOk(ApiClient.ApiCall call, String what) {
        if (call.code() != 0) {
            throw new AssertionError(what + " 应当成功，实际：" + call.body());
        }
    }

    /** 提交一条目录外服务提案（用调用方的令牌）。 */
    protected ApiClient.ApiCall submitProposal(String providerToken, String categoryCode, String name,
                                               String priceMin, String priceMax) {
        return api.post("/api/v1/provider/catalog/item-requests",
                new CatalogItemProposalRequest(categoryCode, name, "测试用提案", priceMin, priceMax, "次"),
                providerToken);
    }

    /** 从标准目录勾选并定价。 */
    protected ApiClient.ApiCall createListing(String providerToken, String serviceCode, String price) {
        return api.post("/api/v1/provider/services",
                new com.pethealth.api.provider.ProviderServiceRequest(serviceCode, price), providerToken);
    }
}
