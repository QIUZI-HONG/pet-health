package com.pethealth.boot.assessment;

import com.fasterxml.jackson.databind.JsonNode;
import com.pethealth.provider.service.AssessmentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 增长侧事实源**已接线**之后，拉新与券两项在「平台侧确实没给条件」时的表现。
 *
 * <p>这个类**刻意不导入** {@code AssessmentTestSupport.StubGrowthFacts}：它验的是真实实现
 * （{@code ph-privilege} 的 {@code ProviderGrowthFactsService}）在场的口径。2026-09-30 之前
 * 这个文件叫 {@code AssessmentGrowthUnwiredTest}，那时两类缺项都只能报「数据源未接线」；
 * 接线之后原因变得**可区分**了，这正是它现在要钉住的东西：
 *
 * <ul>
 *   <li>拉新：这家店**没有推广码**（平台还没给它拉新入口）→ 不参与，明细写「还没有给这个服务者
 *       拉新入口」，而**不是**记 0 分——平台没给的数不记在服务者头上（ADR-0050 第四节）；
 *   <li>券：平台券池里**没有可贡献的服务者成本券**（种子两张都是平台补贴券）→ 不参与，
 *       明细写「没有可贡献的服务者成本券」。这与「有券可出而一张没核销」（记 0 分）是两件事；
 *   <li>过程项照常算、权重按参与项重算（考核不会因为两处缺项就整体失效）；
 *   <li>服务者侧的页面照常打开（不是 50300）——查询不依赖缺项的事实源。
 * </ul>
 *
 * <p>「有入口、有券可出」的那条正向路径在 {@code ProviderGrowthFactsTest}（ph-privilege 切片）里验，
 * 因为造那批数据要动邀请与券的表。
 */
class AssessmentGrowthWiringTest extends AssessmentTestSupport {

    @Autowired
    private AssessmentService assessmentService;

    @Test
    @DisplayName("平台没给条件：拉新与券不参与并各自注明原因，过程分照常算")
    void missingPlatformConditionsAreExcludedNotZeroed() {
        ApprovedProvider provider = createApprovedProvider("LIC-WIRED-001");
        // 规则写成完整的一份（不依赖别的用例留下的状态）：权重 40/40/20，拉新与券的达标线都配上
        // ——如果这时候还记 0 分，就是「冤枉服务者」（平台没给条件却记 0，ADR-0050 第四节）
        jdbc.update("UPDATE `assessment_rule` SET `invite_weight` = 40, `coupon_weight` = 40, "
                + "`process_weight` = 20, `invite_target` = 5, `coupon_target` = 10.00, "
                + "`response_minutes_target` = 30 WHERE `id` = 1");
        seedOrder(provider.providerId(), 3, null, 10, LocalDate.of(2026, 8, 3));

        assertThat(assessmentService.calculate(provider.providerId(), "2026-08")).isTrue();

        JsonNode detail = api.get("/api/v1/provider/assessments/2026-08", provider.token()).data();
        JsonNode invite = item(detail, "INVITE");
        assertThat(invite.path("participated").asBoolean()).isFalse();
        assertThat(invite.path("score").isNull()).isTrue();
        // 数据源是**真实实现**（不再是「未接线」），缺的是平台侧的条件
        assertThat(invite.path("data_source").asText()).contains("ProviderGrowthFactsApi");
        assertThat(invite.path("data_source").asText()).doesNotContain("未接线");
        assertThat(invite.path("note").asText()).contains("未参与").contains("拉新入口");
        JsonNode coupon = item(detail, "COUPON");
        assertThat(coupon.path("participated").asBoolean()).isFalse();
        assertThat(coupon.path("score").isNull()).isTrue();
        assertThat(coupon.path("note").asText()).contains("没有可贡献的服务者成本券");
        // 过程项照常（100 分），权重按参与项重算 → 总分 = 过程分
        assertThat(detail.path("participated_weight").asInt()).isEqualTo(20);
        assertThat(item(detail, "PROCESS").path("score").asText()).isEqualTo("100.00");
        assertThat(detail.path("total_score").asText()).isEqualTo("100.00");
    }

    @Test
    @DisplayName("没配达标线：即使入口与券都在，也按「平台还没定要求」不参与")
    void unsetTargetsStayExcluded() {
        ApprovedProvider provider = createApprovedProvider("LIC-WIRED-002");
        jdbc.update("UPDATE `assessment_rule` SET `invite_target` = 0, `coupon_target` = 0.00 WHERE `id` = 1");
        seedOrder(provider.providerId(), 1, null, 10, LocalDate.of(2026, 8, 4));

        assertThat(assessmentService.calculate(provider.providerId(), "2026-08")).isTrue();

        JsonNode detail = api.get("/api/v1/provider/assessments/2026-08", provider.token()).data();
        assertThat(item(detail, "INVITE").path("note").asText()).contains("尚未配置拉新达标线");
        assertThat(item(detail, "COUPON").path("note").asText()).contains("尚未配置券达标线");
    }
}
