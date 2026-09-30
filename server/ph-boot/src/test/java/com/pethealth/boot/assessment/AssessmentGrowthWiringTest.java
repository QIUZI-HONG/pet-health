package com.pethealth.boot.assessment;

import com.fasterxml.jackson.databind.JsonNode;
import com.pethealth.provider.service.AssessmentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 增长侧事实源**未接线**时的降级（ADR-0050 第四节 + ADR-0052 的实现决策）。
 *
 * <p>这个类**刻意不导入** {@code AssessmentTestSupport.StubGrowthFacts}：模拟「ph-privilege 还没有
 * 实现 {@code ProviderGrowthFactsApi}」的真实状态（本轮交付就是这样）。
 * 要验的是那条底线：
 *
 * <ul>
 *   <li>拉新与券两项**不参与**（{@code participated=false, score=null}），
 *       而不是记 0 分——**平台没给的数不记在服务者头上**；
 *   <li>明细里注明是哪条事实缺了（「数据源未接线」），事后查得到；
 *   <li>过程项照常算、权重按参与项重算（考核不会因为一处未接线就整体失效）；
 *   <li>服务者侧的页面照常打开（不是 50300）——查询不依赖未接线的事实源。
 * </ul>
 *
 * <p>订单侧（{@code OrderStatsApi}）已接线，所以过程分是真的算出来的。
 */
class AssessmentGrowthUnwiredTest extends AssessmentTestSupport {

    @Autowired
    private AssessmentService assessmentService;

    @Test
    @DisplayName("事实源未接线：拉新与券不参与并注明原因，过程分照常算")
    void unwiredGrowthFactsAreExcludedNotZeroed() {
        ApprovedProvider provider = createApprovedProvider("LIC-UNWIRED-001");
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
        assertThat(invite.path("data_source").asText()).contains("未接线");
        assertThat(invite.path("note").asText()).contains("未参与");
        JsonNode coupon = item(detail, "COUPON");
        assertThat(coupon.path("participated").asBoolean()).isFalse();
        assertThat(coupon.path("score").isNull()).isTrue();
        assertThat(coupon.path("note").asText()).contains("未接线");
        // 过程项照常（100 分），权重按参与项重算 → 总分 = 过程分
        assertThat(detail.path("participated_weight").asInt()).isEqualTo(20);
        assertThat(item(detail, "PROCESS").path("score").asText()).isEqualTo("100.00");
        assertThat(detail.path("total_score").asText()).isEqualTo("100.00");
    }

}
