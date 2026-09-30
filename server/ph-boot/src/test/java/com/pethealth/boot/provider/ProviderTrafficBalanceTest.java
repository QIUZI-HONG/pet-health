package com.pethealth.boot.provider;

import com.pethealth.boot.assessment.AssessmentTestSupport;
import com.pethealth.boot.support.ApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流量平衡：推荐优先级决定排序（V45），以及区域编码从「永远为空」变成可维护。
 *
 * <p>改造前的两处留白（写在 ADR-0052 / ADR-0035 里，也在上一轮验收报告里）：
 *
 * <ul>
 *   <li>{@code assessment_level_rule.recommend_priority}（等级 → 优先级的映射，运营可改）**只存不用**
 *       ——C 端找店按 {@code provider.level} 排，而等级只有三档且由档位表决定，
 *       改档位映射不会影响排序。于是交付文档 2.3 的「等级决定 AI 推荐优先级」实际是断的；
 *   <li>{@code provider.region_code} **没有任何写入点**，列在库里、索引也在，但永远是 NULL。
 * </ul>
 *
 * <p>这个类钉住接线之后的行为：
 *
 * <ul>
 *   <li>排序读的是 {@code recommend_priority}（不是 level）——**只改 level 不动顺序**；
 *   <li>那一列由**月度考核批算写回**：本类走真实考核路径（{@code AssessmentService#calculate}，
 *       与 {@code AssessmentMonthlyJob} 同一个方法）产生优先级，不再用直接改库伪造那次写回——
 *       伪造过一次：那时断言的值恰好等于列默认值 3，于是「写回没实现」也能全绿；
 *   <li>{@code region_code} 可写、可清空、可筛选，且变更进审核流水（{@code action = 10}）。
 * </ul>
 */
class ProviderTrafficBalanceTest extends AssessmentTestSupport {

    @Test
    @DisplayName("找店排序读推荐优先级：考核写回后顺序改变；只改 level 不影响顺序")
    void browseOrdersByRecommendPriority() {
        ApprovedProvider first = createApprovedProvider("LIC-TRAFFIC-001");
        ApprovedProvider second = createApprovedProvider("LIC-TRAFFIC-002");
        ApprovedProvider third = createApprovedProvider("LIC-TRAFFIC-003");

        // 三家各造一笔已完成订单，差别只在**接单响应时长**（达标线 30 分钟）：
        // 前两家超线 → 过程分低 → 基础档（优先级 3）；第三家 10 分钟 → 过程满分 → 战略合作档（优先级 1）。
        // 拉新与券两项的达标线种子里是 0（未配置）→ 不参与计分，总分等于过程分。
        seedOrder(first.providerId(), 3, null, 120, day(3));
        seedOrder(second.providerId(), 3, null, 120, day(3));
        seedOrder(third.providerId(), 3, null, 10, day(3));

        // 初始三家都是默认的普通档，同级内按评分 → id（确定性顺序）
        assertThat(browseIds()).containsExactly(
                first.providerId(), second.providerId(), third.providerId());

        // 反向断言：绕过考核直接把等级改到最高，顺序**不动**。这正是改造前那条断链的镜像
        // （那时排序读 level，改一次等级就能插队）；用直接改库构造「等级被别的路径改过」这一状态，
        // 断言的却是读路径——这里要证的就是「排序不读 level」。
        jdbc.update("UPDATE `provider` SET `level` = 3 WHERE `id` = ?", third.providerId());
        assertThat(browseIds()).containsExactly(
                first.providerId(), second.providerId(), third.providerId());

        // 走真实考核路径（与月度批算同一个方法）：等级与推荐优先级一起写回门店
        assertThat(assessmentService.calculate(first.providerId(), PERIOD.toString())).isTrue();
        assertThat(assessmentService.calculate(second.providerId(), PERIOD.toString())).isTrue();
        assertThat(assessmentService.calculate(third.providerId(), PERIOD.toString())).isTrue();

        // 写回落地：第三家按档位映射拿到 1（最高），前两家拿到 2（优选）——
        // **两个值都不等于列默认值 3**，所以这些断言不可能靠「没人写」蒙过去。
        // （前两家的接单响应 120 分钟只拖累过程项四个子项里的一个：(25+100+100+100)/4 = 81.25 → 优选档）
        assertThat(priorityOf(third.providerId())).isEqualTo(1);
        assertThat(priorityOf(first.providerId())).isEqualTo(2);
        assertThat(priorityOf(second.providerId())).isEqualTo(2);

        // 于是顺序真的变了：考核 → 写回 → 排序这条链路到这里才第一次走通
        assertThat(browseIds()).containsExactly(
                third.providerId(), first.providerId(), second.providerId());
    }

    @Test
    @DisplayName("区域编码：运营可写、可清空、可筛选；没变化返回 40900；变更进审核流水")
    void regionIsWritableFilterableAndAudited() {
        String admin = adminToken();
        ApprovedProvider hu = createApprovedProvider("LIC-REGION-001");
        ApprovedProvider other = createApprovedProvider("LIC-REGION-002");

        ApiClient.ApiCall assigned = api.put("/api/v1/admin/providers/" + hu.providerId() + "/region",
                Map.of("region_code", "SH-XH"), admin);
        assertCodeOk(assigned, "指定区域");
        assertThat(assigned.data().path("region_code").asText()).isEqualTo("SH-XH");

        // 同值重复指派：40900（不是静默成功——「点了一下但什么也没发生」最难排查）
        ApiClient.ApiCall again = api.put("/api/v1/admin/providers/" + hu.providerId() + "/region",
                Map.of("region_code", "SH-XH"), admin);
        assertThat(again.code()).isEqualTo(40900);

        // 变更进审核流水（append-only，与冻结 / 联盟归属同一张表）
        List<Map<String, Object>> logs = jdbc.queryForList(
                "SELECT `action`, `remark` FROM `provider_review_log` WHERE `provider_id` = ? AND `action` = 10",
                hu.providerId());
        assertThat(logs).hasSize(1);
        assertThat((String) logs.get(0).get("remark")).contains("SH-XH");

        // 格式不合法：40001（区域编码是运营侧的分类，不是自由文本）
        ApiClient.ApiCall bad = api.put("/api/v1/admin/providers/" + hu.providerId() + "/region",
                Map.of("region_code", "sh xh"), admin);
        assertThat(bad.code()).isEqualTo(40001);

        // C 端筛选：先确认不带筛选时两家都在（下面筛掉的那家确实是因为没有区域编码，而不是别的条件）
        assertThat(browseIds()).contains(hu.providerId(), other.providerId());
        ApiClient.ApiCall filtered = api.get("/api/v1/app/providers?region_code=SH-XH", null);
        assertCodeOk(filtered, "按区域筛选");
        assertThat(filtered.data().path("total").asLong()).isEqualTo(1);
        assertThat(filtered.data().path("list").get(0).path("id").asLong()).isEqualTo(hu.providerId());

        // 清空：区域编码可以为空（门店可能从一个片区摘下来）
        ApiClient.ApiCall cleared = api.put("/api/v1/admin/providers/" + hu.providerId() + "/region",
                Map.of(), admin);
        assertCodeOk(cleared, "清空区域");
        assertThat(cleared.data().path("region_code").isNull()).isTrue();
    }

    /** 找店第一页的门店 id 顺序（列表按契约的排序返回）。 */
    private List<Long> browseIds() {
        ApiClient.ApiCall listed = api.get("/api/v1/app/providers?page_size=50", null);
        assertCodeOk(listed, "找店列表");
        List<Long> ids = new ArrayList<>();
        listed.data().path("list").forEach(node -> ids.add(node.path("id").asLong()));
        return ids;
    }

    /** 门店当前的推荐优先级（1 最高）。 */
    private int priorityOf(long providerId) {
        return jdbc.queryForObject("SELECT `recommend_priority` FROM `provider` WHERE `id` = ?",
                Integer.class, providerId);
    }
}
