package com.pethealth.boot.assessment;

import com.pethealth.api.privilege.ProviderGrowthFactsApi;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 带**增长侧事实源**的考核测试基座：给算分用例一个可控的「有效邀请数 / 券池 / 核销数」。
 *
 * <p>{@link ProviderGrowthFactsApi} 的**真实实现已经落地**（{@code ph-privilege} 的
 * {@code ProviderGrowthFactsService}，2026-09-30）。这里的 stub 仍然保留，因为它能精确摆出
 * 「有入口但 0 条」「有券可出但一张没核销」这些**算分用例要逐条对着看的组合**——
 * 用真实现造那些组合要动邀请与券的表，算分用例会因此变成邀请用例的附庸。
 *
 * <p>因为真实现也在容器里，stub 必须标 {@code @Primary}（见下面那段说明）。
 * 真实实现本身的口径在 {@code AssessmentGrowthWiringTest} 与 {@code ProviderGrowthFactsTest} 里验。
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

    /**
     * 把 stub 注册成一个 Bean。
     *
     * <p><b>`@Primary` 是必需的</b>（2026-09-30 起）：真实实现已经落地
     * （{@code ph-privilege} 的 {@code ProviderGrowthFactsService}），于是容器里同时存在两个
     * {@code ProviderGrowthFactsApi}。{@code AssessmentFactSource} 用
     * {@code ObjectProvider.getIfAvailable()} 取实现——**它遇到多个候选会抛
     * NoUniqueBeanDefinitionException**，而不是随便挑一个。所以这里必须标 `@Primary`，
     * 让「算分用例要一个可控的增长侧」这件事仍然成立。
     *
     * <p>不标 `@Primary` 也不会静默走错：会因为多个候选直接炸掉。这正是
     * {@code getIfAvailable()} 与「随便挑一个」的区别——**宁可炸，也不要拿一个不知道是谁的实现算分**。
     */
    @TestConfiguration
    static class StubGrowthFactsConfig {

        @Bean
        @Primary
        ProviderGrowthFactsApi providerGrowthFactsApi() {
            return new StubGrowthFacts();
        }
    }
}
