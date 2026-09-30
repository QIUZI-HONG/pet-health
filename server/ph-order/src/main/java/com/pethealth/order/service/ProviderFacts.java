package com.pethealth.order.service;

import com.pethealth.api.provider.BusinessHour;
import com.pethealth.api.provider.ProviderAccessApi;
import com.pethealth.api.provider.ProviderServiceQueryApi;
import com.pethealth.api.provider.ProviderServiceView;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 服务者侧的**事实查询**：我是哪家店、这家店的服务项与营业时间、门店叫什么。
 *
 * <p>数据都在 ph-provider 的表里，本模块只能经 ph-api 的接口拿（ADR-0006 禁止 join 别人的表）。
 *
 * <p><b>为什么用 {@link ObjectProvider} 而不是直接注入</b>：{@code ProviderAccessApi} 与
 * {@code ProviderServiceQueryApi} 的实现要由 ph-provider 补适配器（本次交付未接线，
 * 见 ADR-0048 的「需要协调」）。直接注入会让**整个应用起不来**（缺 Bean），
 * 那是把一处未接线放大成全线不可用；就地取则是有实现就用、没有就给一句明确的错误
 * （ph-privilege 的 {@code ProviderConsole} 是同一套写法与同一个理由）。
 *
 * <p>为什么把「服务项查不到」与「没接线」分成两种答复：前者是 40400（服务者确实没有这项服务，
 * 或者它还没上架），后者是 50300（平台自身没接好）。混成一种的话，门店会以为自己的服务项丢了
 * ——那是一类查不出来的错。
 */
@Component
public class ProviderFacts {

    private static final Logger log = LoggerFactory.getLogger(ProviderFacts.class);

    private final ObjectProvider<ProviderAccessApi> accessApi;
    private final ObjectProvider<ProviderServiceQueryApi> serviceApi;

    public ProviderFacts(ObjectProvider<ProviderAccessApi> accessApi,
                         ObjectProvider<ProviderServiceQueryApi> serviceApi) {
        this.accessApi = accessApi;
        this.serviceApi = serviceApi;
    }

    /**
     * 当前服务者后台账号绑定的服务者 id。
     *
     * @throws BusinessException 40400 没有绑定（并入「不存在」，免得用 id 探测门店）、
     *                           50300 身份解析未接线
     */
    public long requireProviderId(long userId) {
        ProviderAccessApi api = requireWired(accessApi.getIfAvailable(), ProviderAccessApi.class);
        return api.findProviderId(userId).orElseThrow(
                () -> BusinessException.notFound("当前账号还没有关联的服务者"));
    }

    /**
     * 一批服务者的名字（订单列表 / 详情要显示门店名）。
     *
     * <p>没接线时返回空 Map：列表少一列，总比整页 50300 好——订单本身的数据仍然有值
     * （门店名是**现取**的展示字段，见迁移 V29 的注释）。
     */
    public Map<Long, String> providerNames(Collection<Long> providerIds) {
        ProviderAccessApi api = accessApi.getIfAvailable();
        if (api == null || providerIds == null || providerIds.isEmpty()) {
            return Map.of();
        }
        return api.providerNames(providerIds);
    }

    /**
     * 本店**已上架**的服务项（下单与号源查询的前置校验）。
     *
     * @throws BusinessException 40400 不存在 / 不属于该门店 / 还没上架（三种都按不存在处理）、
     *                           50300 服务项查询未接线
     */
    public ProviderServiceView requireListedService(long providerId, long serviceId) {
        ProviderServiceQueryApi api = requireWired(serviceApi.getIfAvailable(), ProviderServiceQueryApi.class);
        return api.findListedService(providerId, serviceId).orElseThrow(
                () -> BusinessException.notFound("服务项不存在或未上架"));
    }

    /** 同上，但查不到时返回空（号源之外的只读路径用，避免为一处展示失败整页拒绝）。 */
    public Optional<ProviderServiceView> findListedService(long providerId, long serviceId) {
        ProviderServiceQueryApi api = serviceApi.getIfAvailable();
        if (api == null) {
            log.warn("服务项查询未接线：ph-provider 需要实现 ProviderServiceQueryApi（见 ADR-0048 的「需要协调」）");
            return Optional.empty();
        }
        return api.findListedService(providerId, serviceId);
    }

    /**
     * 门店的营业时段；**查不到（没配置）时返回空集合**。
     *
     * <p>空集合的语义是「那天不可预约」而不是「全天可约」：后者会让一个还没填营业时间的门店
     * 被约爆，而「没填营业时间」在本项目里本来就是「不能接单」的意思。
     */
    public List<BusinessHour> businessHours(long providerId) {
        ProviderServiceQueryApi api = requireWired(serviceApi.getIfAvailable(), ProviderServiceQueryApi.class);
        List<BusinessHour> hours = api.businessHoursOf(providerId);
        return hours == null ? List.of() : hours;
    }

    private <T> T requireWired(T api, Class<T> type) {
        if (api == null) {
            log.warn("{} 未接线：ph-provider 需要提供实现（见 ADR-0048 的「需要协调」）", type.getSimpleName());
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    type.getSimpleName() + " 尚未接线，请联系平台");
        }
        return api;
    }
}
