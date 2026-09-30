package com.pethealth.boot.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethealth.account.auth.JwtService;
import com.pethealth.api.account.UserQueryApi;
import com.pethealth.api.app.UserProfile;
import com.pethealth.api.order.OrderCreateRequest;
import com.pethealth.api.privilege.CouponDtos.CouponView;
import com.pethealth.api.privilege.CouponFactsApi;
import com.pethealth.api.provider.BusinessHour;
import com.pethealth.api.provider.ProviderAccessApi;
import com.pethealth.api.provider.ProviderServiceQueryApi;
import com.pethealth.api.provider.ProviderServiceView;
import com.pethealth.api.record.PetFactsApi;
import com.pethealth.boot.provider.ProviderApiTestSupport;
import com.pethealth.boot.support.ApiClient;
import com.pethealth.catalog.api.CatalogQueryApi;
import com.pethealth.common.crypto.FieldCipher;
import com.pethealth.common.security.LoginDomain;
import com.pethealth.common.util.Masking;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 订单切片（#77 / #107 / #109）集成测试的公共基座。
 *
 * <p><b>为什么要在这里补四个 {@code *Api} 的实现</b>：它们是 ph-api 里的跨模块只读接口，
 * 按契约应由提供方模块补适配器（见 ADR-0048 的「需要协调」），而 ph-provider / ph-account /
 * ph-record / ph-privilege 都不在本次改动范围内。这里的 stub 就是那些适配器的**等价物**：
 * 读的还是同一批表（{@code provider_service} / {@code provider} / {@code user} / {@code pet} /
 * {@code coupon}），所以被这些用例验过的链路，在真适配器落地后仍然成立。生产环境没有它们，
 * 相应调用会明确报「未接线」，而不是静默按「查不到」处理。
 *
 * <p><b>服务者身份（{@link ProviderAccessApi}）也在这里补</b>，理由同上——与 ph-privilege 的
 * 测试基座是同一套写法（{@code PrivilegeTestSupport.StubProviderAccess}）。
 *
 * <p>清理：{@code provider} 那批表由父类清；这里清订单的四张表。
 * **号源行不预置**（懒建是设计的一部分），所以测试要自己造场景。
 */
@Import(OrderTestSupport.StubCrossModuleApis.class)
public abstract class OrderTestSupport extends ProviderApiTestSupport {

    /** 标准目录里“洗护”分类下的一个种子项目（V21 种入）。 */
    protected static final String SERVICE_CODE = "GR-001";

    /** 一个门店的营业时间：整周 09:00–18:00（测试用，避免工作日挑不对）。 */
    protected static final String OPEN_TIME = "09:00";
    protected static final String CLOSE_TIME = "18:00";

    @Autowired
    protected ObjectMapper objectMapper;

    @BeforeEach
    void cleanOrderData() {
        // 顺序：先子后主（本切片全是逻辑引用，没有物理外键，但这个顺序读起来最清楚）
        jdbc.execute("DELETE FROM `order_review`");
        jdbc.execute("DELETE FROM `order_photo`");
        jdbc.execute("DELETE FROM `order_photo_slot`");
        jdbc.execute("DELETE FROM `order`");
        jdbc.execute("DELETE FROM `appointment_slot`");
    }

    // ---------------------------------------------------------------- 造数据

    /** 一个已入驻、已设营业时间、有已上架服务项的门店。 */
    protected record Shop(long providerId, String token, long serviceId, String serviceCode, String price) {
    }

    /**
     * 造一个「能接单」的门店：入驻审核通过 → 设营业时间（整周 09:00–18:00）
     * → 从标准目录勾选定价 → 运营审核通过（直接变成已上架）。
     */
    protected Shop createShop(String licenseNo, String price) {
        ApprovedProvider provider = createApprovedProvider(licenseNo);
        setBusinessHours(provider.token());
        ApiClient.ApiCall listing = createListing(provider.token(), SERVICE_CODE, price);
        assertCodeOk(listing, "选品定价");
        long serviceId = listing.data().path("id").asLong();
        ApiClient.ApiCall approved = api.post("/api/v1/admin/service-listings/" + serviceId + "/approve",
                Map.of("remark", "价在区间内"), adminToken());
        assertCodeOk(approved, "上架审核");
        return new Shop(provider.providerId(), provider.token(), serviceId, SERVICE_CODE, price);
    }

    /** 给门店设一整周的营业时间。 */
    protected void setBusinessHours(String providerToken) {
        List<Map<String, Object>> hours = new ArrayList<>();
        for (int day = 1; day <= 7; day++) {
            hours.add(Map.of("day_of_week", day, "open_time", OPEN_TIME, "close_time", CLOSE_TIME));
        }
        assertCodeOk(api.put("/api/v1/provider/profile/business-hours", Map.of("hours", hours),
                providerToken), "设置营业时间");
    }

    /** 明天（订单都约在明天，避开「时段已过去」）。 */
    protected LocalDate tomorrow() {
        return LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(1);
    }

    /** 下单（用 C 端令牌）。返回原始响应，由用例自己断言。 */
    protected ApiClient.ApiCall placeOrder(String userToken, long petId, Shop shop, LocalDate date,
                                          String startTime) {
        return placeOrder(userToken, petId, shop, date, startTime, null);
    }

    /** 下单，可带券。 */
    protected ApiClient.ApiCall placeOrder(String userToken, long petId, Shop shop, LocalDate date,
                                          String startTime, Long couponId) {
        return api.post("/api/v1/app/orders",
                new OrderCreateRequest(petId, shop.providerId(), shop.serviceId(), date, startTime, couponId, null),
                userToken);
    }

    /** 下单并断言成功，返回订单 id。 */
    protected long orderOk(String userToken, long petId, Shop shop, LocalDate date, String startTime) {
        ApiClient.ApiCall call = placeOrder(userToken, petId, shop, date, startTime);
        assertCodeOk(call, "下单");
        return call.data().path("id").asLong();
    }

    /** 用券下单并断言成功，返回订单 id。 */
    protected long orderOk(String userToken, long petId, Shop shop, LocalDate date, String startTime,
                           Long couponId) {
        ApiClient.ApiCall call = placeOrder(userToken, petId, shop, date, startTime, couponId);
        assertCodeOk(call, "下单（用券）");
        return call.data().path("id").asLong();
    }

    /** 把订单推进到「履约中」（接单 + 核销），返回订单 id。 */
    protected long orderInService(String userToken, String providerToken, long petId, Shop shop,
                                  LocalDate date, String startTime) {
        long orderId = orderOk(userToken, petId, shop, date, startTime);
        assertCodeOk(api.post("/api/v1/provider/orders/" + orderId + "/accept", null, providerToken), "接单");
        assertCodeOk(api.post("/api/v1/provider/orders/" + orderId + "/redeem", null, providerToken), "核销");
        return orderId;
    }

    // ---------------------------------------------------------------- 照片

    /**
     * 直传一张 care 照片并返回 file_id（真实的 presign + 直传 + 落定，不是造行）。
     *
     * <p>用 C 端的文件接口：合同里 {@code biz_type=care} 的入口就是 {@code /api/v1/app/files/presign}，
     * 服务者侧没有第二个上传入口。
     */
    protected long uploadCarePhoto(String uploaderToken, long petId) {
        ApiClient.ApiCall presign = api.post("/api/v1/app/files/presign",
                Map.of("biz_type", "care", "pet_id", petId,
                        "items", List.of(Map.of("mime", "image/jpeg"))), uploaderToken);
        assertCodeOk(presign, "申请上传凭证");
        String uploadUrl = presign.data().get(0).path("upload_url").asText();
        long fileId = presign.data().get(0).path("file_id").asLong();
        if (api.putBinary(uploadUrl, jpeg(600, 400)).status() != 204) {
            throw new AssertionError("直传应当返回 204");
        }
        return fileId;
    }

    /** 保存一个照片槽位（整体替换）。 */
    protected ApiClient.ApiCall saveSlot(String providerToken, long orderId, int slot,
                                         List<Long> fileIds, String remark) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("file_ids", fileIds);
        if (remark != null) {
            body.put("remark", remark);
        }
        return api.put("/api/v1/provider/orders/" + orderId + "/photo-slots/" + slot, body, providerToken);
    }

    /** 三道照片墙各传一张（报工的前置条件）。 */
    protected void fillPhotoWall(String providerToken, String uploaderToken, long orderId, long petId) {
        for (int slot = 1; slot <= 3; slot++) {
            long fileId = uploadCarePhoto(uploaderToken, petId);
            assertCodeOk(saveSlot(providerToken, orderId, slot, List.of(fileId), "第 " + slot + " 道"),
                    "保存照片槽位 " + slot);
        }
    }

    // ---------------------------------------------------------------- 断言辅助

    protected Map<String, Object> orderRow(long orderId) {
        return jdbc.queryForMap("SELECT * FROM `order` WHERE `id` = ?", orderId);
    }

    /** 号源上还剩几个（-1 表示那行还不存在，即还没被占用过）。 */
    protected int slotBooked(long providerId, long serviceId, LocalDate date, String startTime) {
        List<Integer> booked = jdbc.queryForList("SELECT `booked_count` FROM `appointment_slot` "
                        + "WHERE `provider_id` = ? AND `service_id` = ? AND `slot_date` = ? AND `start_time` = ?",
                Integer.class, providerId, serviceId, date, startTime);
        return booked.isEmpty() ? 0 : booked.get(0);
    }

    /** C 端令牌。 */
    protected String userToken(String phone) {
        return api.registerAndGetAccessToken(phone);
    }

    /**
     * 造一张**平台补贴券模板**（成本归平台，不占服务者的贡献额度）。
     *
     * <p>下单只会遇到平台补贴券：服务者成本的券是「某某店的券」，
     * 券侧的门店校验不属于本切片的范围。
     */
    protected long createSubsidyTemplate(String code, String faceValue, String minAmount) {
        ApiClient.ApiCall created = api.post("/api/v1/admin/coupon-templates", Map.of(
                "code", code, "name", "补贴券 " + code, "face_value", faceValue, "min_amount", minAmount,
                "valid_days", 30, "cost_bearer", 2), adminToken());
        assertCodeOk(created, "建平台补贴券模板");
        return created.data().path("id").asLong();
    }

    /** 运营定向发一张平台补贴券给某个用户，返回券实例 id。 */
    protected long issueCoupon(long userId, long templateId) {
        ApiClient.ApiCall issued = api.post("/api/v1/admin/coupons",
                Map.of("user_id", userId, "template_id", templateId), adminToken());
        assertCodeOk(issued, "定向发券");
        return issued.data().path("id").asLong();
    }

    /** 券当前的状态（1 待使用 / 2 已锁定 / 3 已核销 / 4 已过期）与它占着的订单。 */
    protected Map<String, Object> couponRow(long couponId) {
        return jdbc.queryForMap("SELECT `status`, `locked_order_id`, `redeemed_order_id` "
                + "FROM `coupon` WHERE `id` = ?", couponId);
    }

    /** 建一个**测试专用**的目录项（分类与项目都以 TEST / TX- 打头，父类清理时会删掉）。 */
    protected long createTestCatalogItem(String itemCode, String priceMin, String priceMax) {
        ApiClient.ApiCall category = api.post("/api/v1/admin/catalog/categories",
                Map.of("code", "TEST1", "item_code_prefix", "TX", "name", "测试分类"), adminToken());
        assertCodeOk(category, "建测试分类");
        ApiClient.ApiCall item = api.post("/api/v1/admin/catalog/items", Map.of(
                "code", itemCode, "category_code", "TEST1", "name", "测试服务项 " + itemCode,
                "price_min", priceMin, "price_max", priceMax, "price_unit", "次"), adminToken());
        assertCodeOk(item, "建测试目录项");
        return item.data().path("id").asLong();
    }

    /** 运营收窄目录项的价格区间（ADR-0034 决定 8：收窄不自动下架存量，于是下单时要复校）。 */
    protected void narrowCatalogRange(long itemId, String itemCode, String priceMin, String priceMax) {
        assertCodeOk(api.put("/api/v1/admin/catalog/items/" + itemId, Map.of(
                "code", itemCode, "category_code", "TEST1", "name", "测试服务项 " + itemCode,
                "price_min", priceMin, "price_max", priceMax, "price_unit", "次"), adminToken()),
                "收窄价格区间");
    }

    /** 注册一个 C 端账号，返回它的登录令牌与用户 id。 */
    protected UserActor createUser(String phone) {
        ApiClient.ApiCall registered = api.register(phone);
        assertCodeOk(registered, "注册");
        return new UserActor(registered.data().path("user").path("id").asLong(),
                registered.data().path("access_token").asText());
    }

    protected record UserActor(long userId, String token) {
    }

    // ---------------------------------------------------------------- 图片字节

    protected static byte[] jpeg(int width, int height) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image(width, height), "jpg", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static BufferedImage image(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.decode("#0ea382"));
        g.fillRect(0, 0, width, height);
        g.dispose();
        return image;
    }

    // ---------------------------------------------------------------- 跨模块适配器的测试实现

    /**
     * 四个跨模块只读接口的测试实现——**与各提供方要补的适配器等价**：读同一批表，
     * 只是搬到了测试里。生产代码不依赖它们。
     */
    @TestConfiguration
    static class StubCrossModuleApis {

        @Bean
        ProviderAccessApi providerAccessApi(JdbcTemplate jdbc) {
            return new ProviderAccessApi() {

                @Override
                public Optional<Long> findProviderId(long userId) {
                    List<Long> ids = jdbc.queryForList("SELECT provider_id FROM provider_user "
                                    + "WHERE user_id = ? AND status = 1 AND is_deleted = 0 ORDER BY id LIMIT 1",
                            Long.class, userId);
                    return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
                }

                @Override
                public boolean isAdmin(long userId) {
                    List<Integer> roles = jdbc.queryForList("SELECT role FROM provider_user "
                                    + "WHERE user_id = ? AND status = 1 AND is_deleted = 0 ORDER BY id LIMIT 1",
                            Integer.class, userId);
                    return !roles.isEmpty() && roles.get(0) == 1;
                }

                @Override
                public Map<Long, String> providerNames(Collection<Long> providerIds) {
                    Map<Long, String> names = new HashMap<>();
                    if (providerIds == null || providerIds.isEmpty()) {
                        return names;
                    }
                    String placeholders = String.join(",", providerIds.stream().map(id -> "?").toList());
                    jdbc.query("SELECT id, name FROM provider WHERE is_deleted = 0 AND id IN ("
                            + placeholders + ")", (RowCallbackHandler) rs -> names.put(rs.getLong("id"), rs.getString("name")),
                            providerIds.toArray());
                    return names;
                }
            };
        }

        /** 与 ph-provider 要补的适配器等价：服务项按 (门店, 已上架) 查，营业时间读 {@code provider.business_hours}。 */
        @Bean
        ProviderServiceQueryApi providerServiceQueryApi(JdbcTemplate jdbc, CatalogQueryApi catalog,
                                                        ObjectMapper objectMapper) {
            return new ProviderServiceQueryApi() {

                @Override
                public Optional<ProviderServiceView> findListedService(long providerId, long serviceId) {
                    List<Map<String, Object>> rows = jdbc.queryForList(
                            "SELECT id, provider_id, service_code, price FROM provider_service "
                                    + "WHERE id = ? AND provider_id = ? AND status = 1 AND is_deleted = 0",
                            serviceId, providerId);
                    if (rows.isEmpty()) {
                        return Optional.empty();
                    }
                    Map<String, Object> row = rows.get(0);
                    String code = (String) row.get("service_code");
                    String name = catalog.findItem(code).map(CatalogQueryApi.ItemInfo::name).orElse(null);
                    return Optional.of(new ProviderServiceView(
                            ((Number) row.get("id")).longValue(),
                            ((Number) row.get("provider_id")).longValue(),
                            null, code, name, null, null,
                            String.valueOf(row.get("price")), null, null, null,
                            1, null, null, null, null));
                }

                @Override
                public List<BusinessHour> businessHoursOf(long providerId) {
                    List<String> raw = jdbc.queryForList("SELECT business_hours FROM provider WHERE id = ?",
                            String.class, providerId);
                    if (raw.isEmpty() || raw.get(0) == null) {
                        return List.of();
                    }
                    // 注意这里的键名是 **camelCase**：ph-provider 的 BusinessHours 用的是自己的
                    // 裸 ObjectMapper（没配 SNAKE_CASE），所以库里的 JSON 是 `dayOfWeek / openTime /
                    // closeTime`——与 HTTP 层的 snake_case 不同。测试按库里的真实形状读，
                    // 不假设它与接口层一致
                    try {
                        List<BusinessHour> hours = new ArrayList<>();
                        for (JsonNode node : objectMapper.readTree(raw.get(0))) {
                            hours.add(new BusinessHour(node.path("dayOfWeek").asInt(),
                                    node.path("openTime").asText(), node.path("closeTime").asText()));
                        }
                        return hours;
                    } catch (Exception e) {
                        throw new IllegalStateException("营业时间 JSON 解析失败：" + raw.get(0), e);
                    }
                }
            };
        }

        /** 与 ph-account 要补的适配器等价：昵称 + 脱敏手机号，手机号按 lookup_hash 等值查（ADR-0013）。 */
        @Bean
        UserQueryApi userQueryApi(JdbcTemplate jdbc, FieldCipher fieldCipher) {
            return new UserQueryApi() {

                @Override
                public Map<Long, UserProfile> profiles(Collection<Long> userIds) {
                    Map<Long, UserProfile> profiles = new HashMap<>();
                    if (userIds == null || userIds.isEmpty()) {
                        return profiles;
                    }
                    String placeholders = String.join(",", userIds.stream().map(id -> "?").toList());
                    jdbc.query("SELECT id, nickname, phone_enc, created_at FROM user WHERE is_deleted = 0 AND id IN ("
                                    + placeholders + ")",
                            (RowCallbackHandler) rs -> profiles.put(rs.getLong("id"), new UserProfile(
                                    rs.getLong("id"),
                                    Masking.phone(fieldCipher.decrypt(rs.getString("phone_enc"))),
                                    rs.getString("nickname"),
                                    null, null, null,
                                    rs.getTimestamp("created_at") == null ? null
                                            : rs.getTimestamp("created_at").toLocalDateTime())),
                            userIds.toArray());
                    return profiles;
                }

                @Override
                public Optional<Long> findUserIdByPhone(String phone) {
                    List<Long> ids = jdbc.queryForList("SELECT id FROM user WHERE phone_hash = ? AND is_deleted = 0",
                            Long.class, fieldCipher.lookupHash(phone));
                    return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
                }

                /**
                 * 与生产实现同一个口径：解密后取前 7 位，解不开就没有信号。
                 *
                 * <p>订单这些用例不判反作弊，但**桩必须与接口同形**——它存在的意义是让消费方在
                 * 「不依赖 ph-account」的前提下拿到与生产一致的形状。少实现一个方法编译期就红，
                 * 这是这道桩与真实实现之间唯一的强制同步点。
                 */
                @Override
                public Optional<String> phoneSegmentOf(long userId) {
                    List<String> phones = jdbc.queryForList(
                            "SELECT phone_enc FROM user WHERE id = ? AND is_deleted = 0", String.class, userId);
                    if (phones.isEmpty()) {
                        return Optional.empty();
                    }
                    String phone = fieldCipher.decrypt(phones.get(0));
                    return phone != null && phone.length() >= 7
                            ? Optional.of(phone.substring(0, 7)) : Optional.empty();
                }
            };
        }

        /** 与 ph-record 要补的适配器等价：宠物昵称与物种。 */
        @Bean
        PetFactsApi petFactsApi(JdbcTemplate jdbc) {
            return new PetFactsApi() {

                @Override
                public Map<Long, String> petNames(Collection<Long> petIds) {
                    Map<Long, String> names = new HashMap<>();
                    if (petIds == null || petIds.isEmpty()) {
                        return names;
                    }
                    jdbc.query("SELECT id, name FROM pet WHERE is_deleted = 0 AND id IN ("
                                    + placeholders(petIds) + ")",
                            (RowCallbackHandler) rs -> names.put(rs.getLong("id"), rs.getString("name")),
                            petIds.toArray());
                    return names;
                }

                @Override
                public Map<Long, Integer> petSpecies(Collection<Long> petIds) {
                    Map<Long, Integer> species = new HashMap<>();
                    if (petIds == null || petIds.isEmpty()) {
                        return species;
                    }
                    jdbc.query("SELECT id, species FROM pet WHERE is_deleted = 0 AND id IN ("
                                    + placeholders(petIds) + ")",
                            (RowCallbackHandler) rs -> species.put(rs.getLong("id"), rs.getInt("species")),
                            petIds.toArray());
                    return species;
                }
            };
        }

        /** 与 ph-privilege 要补的适配器等价：券实例 + 模板快照。 */
        @Bean
        CouponFactsApi couponFactsApi(JdbcTemplate jdbc) {
            return couponIds -> {
                Map<Long, CouponView> coupons = new HashMap<>();
                if (couponIds == null || couponIds.isEmpty()) {
                    return coupons;
                }
                jdbc.query("SELECT c.id, c.code, c.user_id, c.provider_id, c.template_id, "
                                + "t.code AS template_code, t.name AS template_name, c.face_value, c.min_amount, "
                                + "c.source, c.contribution_id, c.status, c.valid_from, c.valid_until, "
                                + "c.issued_at, c.redeemed_at, c.created_at "
                                + "FROM coupon c JOIN coupon_template t ON t.id = c.template_id "
                                + "WHERE c.is_deleted = 0 AND c.id IN (" + placeholders(couponIds) + ")",
                        (RowCallbackHandler) rs -> coupons.put(rs.getLong("id"), new CouponView(
                                rs.getLong("id"), rs.getString("code"), rs.getLong("user_id"),
                                (Long) rs.getObject("provider_id"), null,
                                rs.getLong("template_id"), rs.getString("template_code"), rs.getString("template_name"),
                                money(rs.getBigDecimal("face_value")), money(rs.getBigDecimal("min_amount")),
                                rs.getInt("source"), (Long) rs.getObject("contribution_id"), rs.getInt("status"),
                                toLocal(rs.getTimestamp("valid_from")), toLocal(rs.getTimestamp("valid_until")),
                                toLocal(rs.getTimestamp("issued_at")), toLocal(rs.getTimestamp("redeemed_at")),
                                toLocal(rs.getTimestamp("created_at")))),
                        couponIds.toArray());
                return coupons;
            };
        }

        private static String placeholders(Collection<Long> ids) {
            return String.join(",", ids.stream().map(id -> "?").toList());
        }

        private static String money(BigDecimal value) {
            return value == null ? null : value.toPlainString();
        }

        private static java.time.LocalDateTime toLocal(java.sql.Timestamp timestamp) {
            return timestamp == null ? null : timestamp.toLocalDateTime();
        }
    }
}
