package com.pethealth.provider.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pethealth.api.provider.ProviderAccessApi;
import com.pethealth.provider.domain.Provider;
import com.pethealth.provider.domain.ProviderUser;
import com.pethealth.provider.mapper.ProviderMapper;
import com.pethealth.provider.mapper.ProviderUserMapper;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * {@link ProviderAccessApi} 的适配器——ADR-0044「需要协调」那一处接线的落地。
 *
 * <p><b>为什么必须由本模块提供实现</b>：券池（ph-privilege）的服务者侧接口要回答
 * 「这个登录账号是哪家店、是不是管理员」，而 {@code provider} / {@code provider_user} 是
 * ph-provider 的表——ADR-0006 禁止别的模块碰，本仓的既有形态是「跨模块只走对方的 api 接口」。
 * 接口放 ph-api（服务者身份不只券池需要：核销、考核、报表都要），**实现只能由表的拥有者写**。
 * 在它落地之前，服务者侧券接口一律回 50300「服务者身份解析尚未接线」——
 * 测试能过是因为测试里注入了等价桩，生产环境等于这个功能不存在。
 *
 * <p>三个方法都是**转交**，没有新查询逻辑：绑定解析走本模块已有的
 * {@link ProviderAccess#findBound}（口径与门禁完全一致：只认启用中的绑定、多条取最早的一条），
 * 管理员判定读同一张 {@code provider_user}（{@link ProviderUser#isActiveAdmin()}），
 * 名字走 {@link ProviderMapper}（逻辑删除由框架自动带上）。
 *
 * <p>{@code @Primary} 是为了「有第二份实现时以生产这一份为准」：集成测试里有一个等价的桩
 * （{@code PrivilegeTestSupport.StubProviderAccess}），而取用方（{@code ProviderConsole}）
 * 是用 {@code ObjectProvider} 就地取的——两个候选且无主时会抛
 * {@code NoUniqueBeanDefinitionException}，把「有实现」变成 500，正好抵消这次接线的意义。
 */
@Component
@Primary
public class ProviderAccessAdapter implements ProviderAccessApi {

    private final ProviderAccess access;
    private final ProviderMapper providerMapper;
    private final ProviderUserMapper providerUserMapper;

    public ProviderAccessAdapter(ProviderAccess access, ProviderMapper providerMapper,
                                ProviderUserMapper providerUserMapper) {
        this.access = access;
        this.providerMapper = providerMapper;
        this.providerUserMapper = providerUserMapper;
    }

    @Override
    public Optional<Long> findProviderId(long userId) {
        Provider provider = access.findBound(userId);
        return provider == null ? Optional.empty() : Optional.of(provider.getId());
    }

    /**
     * 是不是该服务者的管理员。
     *
     * <p>读的是**启用中的绑定**（停用的绑定与没绑定是同一件事），与
     * {@link ProviderAccess#requireAdmin} 同一条口径：技师（{@code role=2}）不是管理员。
     */
    @Override
    public boolean isAdmin(long userId) {
        ProviderUser binding = providerUserMapper.selectOne(Wrappers.<ProviderUser>lambdaQuery()
                .eq(ProviderUser::getUserId, userId)
                .eq(ProviderUser::getStatus, ProviderUser.STATUS_ACTIVE)
                .orderByAsc(ProviderUser::getId)
                .last("LIMIT 1"));
        return binding != null && binding.isActiveAdmin();
    }

    @Override
    public Map<Long, String> providerNames(Collection<Long> providerIds) {
        Map<Long, String> names = new HashMap<>();
        if (providerIds == null || providerIds.isEmpty()) {
            // 空集合不查库：IN () 会被 MySQL 拒绝，也让调用方的空列表变成一次无意义的往返
            return names;
        }
        for (Provider provider : providerMapper.selectBatchIds(providerIds)) {
            names.put(provider.getId(), provider.getName());
        }
        return names;
    }
}
