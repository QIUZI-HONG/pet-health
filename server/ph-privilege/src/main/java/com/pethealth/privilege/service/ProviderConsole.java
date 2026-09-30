package com.pethealth.privilege.service;

import com.pethealth.api.provider.ProviderAccessApi;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.error.ErrorCode;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.security.LoginDomain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;

/**
 * 服务者后台的门禁：**「我是哪家店、我是不是管理员」**。
 *
 * <p>身份数据属于 ph-provider（{@code provider_user}），本模块只能经
 * {@link ProviderAccessApi}（ph-api 里的跨模块接口）拿，不碰它的表（ADR-0006）。
 *
 * <p><b>为什么用 {@link ObjectProvider} 而不是直接注入</b>：该接口的实现要由 ph-provider
 * 补一个适配器（本次交付未接线，见 ADR-0044 的「需要协调」）。直接注入会让**整个应用起不来**
 * （缺 Bean），那是把一处未接线放大成全线不可用。改成就地取：有实现就用，
 * 没有就回一句明确的错误——服务者问「为什么点不动」时，运维一眼能看出是接线没做，
 * 而不是以为自己的门店不存在（按「没有绑定」静默处理正是这种查不出来的错）。
 */
@Component
public class ProviderConsole {

    private static final Logger log = LoggerFactory.getLogger(ProviderConsole.class);

    private final ObjectProvider<ProviderAccessApi> accessApi;

    public ProviderConsole(ObjectProvider<ProviderAccessApi> accessApi) {
        this.accessApi = accessApi;
    }

    /** 当前登录的服务者后台账号 id（非 provider 域一律 40100）。 */
    public long currentUserId() {
        return CurrentUser.requireDomain(LoginDomain.PROVIDER);
    }

    /**
     * 当前账号绑定的服务者；**要求是管理员**（券的贡献与选品定价同级，ADR-0037 的矩阵里
     * 技师那一列是 ✗）。
     *
     * @throws BusinessException 40400 没有绑定（并入「不存在」，免得用 id 探测）、
     *                           40300 只是技师、50300 接线未完成
     */
    public long requireAdminProviderId() {
        long userId = currentUserId();
        ProviderAccessApi api = requireWired();
        Long providerId = api.findProviderId(userId).orElse(null);
        if (providerId == null) {
            throw BusinessException.notFound("当前账号还没有关联的服务者");
        }
        if (!api.isAdmin(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "该操作只对服务者管理员开放");
        }
        return providerId;
    }

    /** 一批服务者的名字（运营侧展示用）；没接线时返回空 Map（运营页面只是少一列，不该整页失败）。 */
    public Map<Long, String> namesOf(Collection<Long> providerIds) {
        ProviderAccessApi api = accessApi.getIfAvailable();
        if (api == null || providerIds == null || providerIds.isEmpty()) {
            return Map.of();
        }
        return api.providerNames(providerIds);
    }

    private ProviderAccessApi requireWired() {
        ProviderAccessApi api = accessApi.getIfAvailable();
        if (api == null) {
            log.warn("服务者身份解析未接线：ph-provider 需要实现 ProviderAccessApi（见 ADR-0044 的「需要协调」）");
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "服务者身份解析尚未接线，请联系平台");
        }
        return api;
    }
}
