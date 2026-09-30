package com.pethealth.boot.assessment;

import com.pethealth.api.privilege.ProviderGrowthFactsApi;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 带**增长侧事实源**的考核测试基座：给算分用例一个可控的「有效邀请数 / 券池 / 核销数」。
 *
 * <p>{@link ProviderGrowthFactsApi} 是 ph-api 里的跨模块只读接口，按契约应由 ph-privilege 提供实现
 * （**本轮未接线**，见 ADR-0052 的「需要协调」）。这里的 stub 就是那个实现的等价物——
 * 它数的是同一批事实，所以被这些用例验过的算分链路，在真实现落地后仍然成立。
 *
 * <p>{@link AssessmentGrowthUnwiredTest} **刻意继承不带 stub 的基类**，
 * 用来验证「数据源未接线 → 该维度不参与」这条降级口径。
 */
@Import(AssessmentStubSupport.StubGrowthFactsConfig.class)
public abstract class AssessmentStubSupport extends AssessmentTestSupport {

    @Autowired
    protected ProviderGrowthFactsApi growthFacts;

    @BeforeEach
    void resetGrowthFacts() {
        stub().reset();
    }

    /** 测试用的增长侧事实源（可读可写字段）。 */
    protected StubGrowthFacts stub() {
        return (StubGrowthFacts) growthFacts;
    }

    /**
     * {@link ProviderGrowthFactsApi} 的测试实现——**与 ph-privilege 需要补的那个实现等价**。
     * 四个字段的语义与接口注释逐条对应：
     *
     * <ul>
     *   <li>{@code effectiveInvites = null} 表示**平台侧还没有给这个服务者拉新入口**（不参与）；
     *       给 0 表示「有入口但一条没有」（该做而没做 → 记 0 分）；
     *   <li>{@code contributableTemplates = 0} 表示**券池里一张可贡献的券都没有**（券维度不参与）；
     *   <li>{@code completionRate} 与 {@code couponRedeemed} 相乘就是考核的券项达成量。
     * </ul>
     */
    static class StubGrowthFacts implements ProviderGrowthFactsApi {

        private Integer effectiveInvites;
        private int contributableTemplates = 1;
        private int couponRedeemed;
        private BigDecimal completionRate = BigDecimal.ZERO;

        void reset() {
            this.effectiveInvites = null;
            this.contributableTemplates = 1;
            this.couponRedeemed = 0;
            this.completionRate = BigDecimal.ZERO;
        }

        void setInvites(Integer effectiveInvites) {
            this.effectiveInvites = effectiveInvites;
        }

        void setContributableTemplates(int contributableTemplates) {
            this.contributableTemplates = contributableTemplates;
        }

        void setCoupon(int redeemedCount, String completionRate) {
            this.couponRedeemed = redeemedCount;
            this.completionRate = new BigDecimal(completionRate);
        }

        @Override
        public Integer effectiveInvites(long providerId, LocalDate from, LocalDate to) {
            return effectiveInvites;
        }

        @Override
        public int contributableCouponTemplates() {
            return contributableTemplates;
        }

        @Override
        public int couponRedeemedCount(long providerId, LocalDate from, LocalDate to) {
            return couponRedeemed;
        }

        @Override
        public BigDecimal couponCompletionRate(long providerId, LocalDate from, LocalDate to) {
            return completionRate;
        }
    }

    /** 把 stub 注册成一个 Bean（与 {@code PrivilegeTestSupport.StubProviderAccess} 同一写法）。 */
    @TestConfiguration
    static class StubGrowthFactsConfig {

        @Bean
        ProviderGrowthFactsApi providerGrowthFactsApi() {
            return new StubGrowthFacts();
        }
    }
}
