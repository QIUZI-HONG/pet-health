package com.pethealth.provider.service;

import com.pethealth.api.privilege.ProviderGrowthFactsApi;
import com.pethealth.order.api.OrderStatsApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 考核取数的**唯一入口**：把两个只读接口（订单侧已接线、增长侧待接线）收成一份事实。
 *
 * <p>为什么不许考核 service 自己去调那两个接口：取数有三条容易写歪的地方，收在这里一处改得动：
 *
 * <ol>
 *   <li><b>状态码只有一份解释</b>：核销率要「履约中 + 已完成」、报工完整率要看「已报工 ÷ 已核销」、
 *       取消率只认服务者单方取消（{@code cancelled_by = 2}）。散在算分代码里就会出现
 *       「这个指标按 2 算、那个按 2+3 算」这种没法发现的偏差；
 *   <li><b>null 与 0 不在这里被归一</b>：接口说 null 就是 null（没有可算的均值），
 *       补 0 会把「没有数据」变成「数据是 0 分」——那正是 ADR-0049 要避免的；
 *   <li><b>未接线要报得出来</b>：增长侧用 {@link ObjectProvider} 就地取实现（直接注入会让整个
 *       应用起不来），取不到时给出 {@code growthWired = false}，明细里注明——而不是静默按 0 分。
 * </ol>
 */
@Component
public class AssessmentFactSource {

    private static final Logger log = LoggerFactory.getLogger(AssessmentFactSource.class);

    private final OrderStatsApi orderStats;
    private final ObjectProvider<ProviderGrowthFactsApi> growthFacts;

    public AssessmentFactSource(OrderStatsApi orderStats,
                               ObjectProvider<ProviderGrowthFactsApi> growthFacts) {
        this.orderStats = orderStats;
        this.growthFacts = growthFacts;
    }

    /** 取一个服务者在一个账期里的事实。**只读**：两个接口都是 readOnly 的统计。 */
    @Transactional(readOnly = true)
    public AssessmentFacts collect(long providerId, LocalDate from, LocalDate to) {
        long total = orderStats.countByProvider(providerId, from, to, null);
        long inProgress = orderStats.countByProvider(providerId, from, to, OrderStatsApi.STATUS_IN_SERVICE);
        long done = orderStats.countByProvider(providerId, from, to, OrderStatsApi.STATUS_COMPLETED);
        long cancelled = orderStats.countCancelledByProvider(providerId, from, to);
        Long averageResponse = orderStats.averageResponseMinutes(providerId, from, to);

        ProviderGrowthFactsApi growth = growthFacts.getIfAvailable();
        if (growth == null) {
            log.info("考核取数：增长侧事实源未接线（ph-privilege 需实现 ProviderGrowthFactsApi），"
                    + "providerId={} period={}~{} 的拉新与券两项按「未参与」处理", providerId, from, to);
            return new AssessmentFacts(from, to, total, inProgress, done, cancelled, averageResponse,
                    false, null, 0, 0, BigDecimal.ZERO);
        }
        return new AssessmentFacts(from, to, total, inProgress, done, cancelled, averageResponse,
                true,
                growth.effectiveInvites(providerId, from, to),
                growth.contributableCouponTemplates(),
                growth.couponRedeemedCount(providerId, from, to),
                growth.couponCompletionRate(providerId, from, to));
    }
}
