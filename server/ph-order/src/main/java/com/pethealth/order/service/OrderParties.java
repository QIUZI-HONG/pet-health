package com.pethealth.order.service;

import com.pethealth.api.account.UserQueryApi;
import com.pethealth.api.app.UserProfile;
import com.pethealth.api.privilege.CouponDtos.CouponView;
import com.pethealth.api.privilege.CouponFactsApi;
import com.pethealth.api.record.PetFactsApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * 订单两侧的**事实查询**：预约人（账号）、宠物（档案）、券（券池）。
 *
 * <p>三个来源分属 ph-account / ph-record / ph-privilege，本模块一律经 ph-api 的接口读
 * （ADR-0006）。这些查询都是**展示与定位**用的：它们查不到时**不让整页失败**——
 * 订单本身的数据仍然完整（快照在订单行上），少的是「预约人叫什么、宠物什么物种」这类现取字段。
 * 唯一的例外是 {@link #userIdByPhone}：核销的定位入口如果静默查不到，门店会以为
 * 「这个人没有订单」，所以那个方法在没接线时给出明确的错误（见下）。
 *
 * <p>与 {@link ProviderFacts} 同样的理由用 {@link ObjectProvider}：这三个适配器本次未接线
 * （见 ADR-0048 的「需要协调」），直接注入会让整个应用起不来。
 */
@Component
public class OrderParties {

    private static final Logger log = LoggerFactory.getLogger(OrderParties.class);

    /** 脱敏的手机号在这里补：{@code UserProfile.phone} 已经是脱敏值，空表示查不到账号。 */
    private static final String UNKNOWN_PHONE = "";

    private final ObjectProvider<UserQueryApi> userApi;
    private final ObjectProvider<PetFactsApi> petApi;
    private final ObjectProvider<CouponFactsApi> couponApi;

    public OrderParties(ObjectProvider<UserQueryApi> userApi, ObjectProvider<PetFactsApi> petApi,
                        ObjectProvider<CouponFactsApi> couponApi) {
        this.userApi = userApi;
        this.petApi = petApi;
        this.couponApi = couponApi;
    }

    /** 一批预约人的资料（昵称 + 脱敏手机号）；没接线时返回空 Map（那两列留空）。 */
    public Map<Long, UserProfile> profiles(Collection<Long> userIds) {
        UserQueryApi api = userApi.getIfAvailable();
        if (api == null || userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        return api.profiles(userIds);
    }

    /**
     * 按**完整手机号**查用户 id（核销的三个定位入口之一，ADR-0038 第二节）。
     *
     * <p>没接线时**不返回空**：空意味着「这个号码没有订单」，门店会据此把客户打发走。
     * 未接线时平台的自我认知应当是「我查不了」，所以抛 50300（见 {@link ProviderFacts} 的同一条取舍）。
     */
    public Optional<Long> userIdByPhone(String phone) {
        UserQueryApi api = userApi.getIfAvailable();
        if (api == null) {
            log.warn("手机号查询未接线：ph-account 需要实现 UserQueryApi（见 ADR-0048 的「需要协调」）");
            throw new com.pethealth.common.error.BusinessException(
                    com.pethealth.common.error.ErrorCode.SERVICE_UNAVAILABLE,
                    "按手机号搜索尚未接线，请联系平台");
        }
        return api.findUserIdByPhone(phone);
    }

    /** 一批宠物的昵称；没接线时返回空 Map（`pet_name` 是订单行上的快照，这里只是兜底补齐）。 */
    public Map<Long, String> petNames(Collection<Long> petIds) {
        PetFactsApi api = petApi.getIfAvailable();
        if (api == null || petIds == null || petIds.isEmpty()) {
            return Map.of();
        }
        return api.petNames(petIds);
    }

    /** 一批宠物的物种码（1 犬 / 2 猫）；没接线时返回空 Map。 */
    public Map<Long, Integer> petSpecies(Collection<Long> petIds) {
        PetFactsApi api = petApi.getIfAvailable();
        if (api == null || petIds == null || petIds.isEmpty()) {
            return Map.of();
        }
        return api.petSpecies(petIds);
    }

    /**
     * 一批券的完整视图（订单详情里的券卡片：面额、门槛、有效期、状态）。
     *
     * <p>没接线时返回空 Map：券卡片为空，而订单上的 {@code coupon_discount} 仍是对的
     * （它是下单时的面额快照）——用户看得出来「用了多少」，只是看不到那张券的细节。
     */
    public Map<Long, CouponView> coupons(Collection<Long> couponIds) {
        CouponFactsApi api = couponApi.getIfAvailable();
        if (api == null || couponIds == null || couponIds.isEmpty()) {
            return Map.of();
        }
        return api.couponsOf(couponIds);
    }

    /** 单张券；査不到（或没接线）时为空。 */
    public Optional<CouponView> coupon(Long couponId) {
        if (couponId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(coupons(java.util.List.of(couponId)).get(couponId));
    }

    /** 查不到账号时手机号的展示值：空串（不要把这个字段填成 "{@code null}" 之外的猜测值）。 */
    public static String phoneOf(UserProfile profile) {
        return profile == null || profile.phone() == null ? UNKNOWN_PHONE : profile.phone();
    }
}
