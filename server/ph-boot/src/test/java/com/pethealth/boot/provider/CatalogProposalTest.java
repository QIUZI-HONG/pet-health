package com.pethealth.boot.provider;

import com.pethealth.api.admin.CatalogItemProposalApproveRequest;
import com.pethealth.boot.support.ApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 目录外服务提案（交付文档 F012 / 2.5，决策见 ADR-0034）：服务者只能提案，
 * 运营审核通过后平台上才有这个项目。
 *
 * <p>覆盖：提案 → 审核队列 → 通过后**生成正式目录项并可被定价**、最终区间由运营给出、
 * 编码自动排号与重号冲突、驳回原因必填、未入驻不能提案、越权（别人的提案 40400）。
 */
class CatalogProposalTest extends ProviderApiTestSupport {

    @Test
    @DisplayName("提案通过：生成正式目录项（自动排号），服务者随即能按它定价")
    void approvedProposalCreatesCatalogItem() {
        String admin = adminToken();
        ApprovedProvider provider = createApprovedProvider("LIC-PROPOSAL-001");

        ApiClient.ApiCall submitted = submitProposal(provider.token(), "HOSPITAL", "宠物针灸",
                "200.00", "400.00");
        assertCodeOk(submitted, "提交提案");
        long proposalId = submitted.data().path("id").asLong();
        assertThat(submitted.data().path("status").asInt()).isZero();
        assertThat(submitted.data().path("provider_name").asText()).isEqualTo("测试动物医院");
        assertThat(submitted.data().path("category_name").asText()).isEqualTo("医院");
        assertThat(submitted.data().path("review_logs")).hasSize(1);

        // 运营队列默认只给待审核
        ApiClient.ApiCall queue = api.get("/api/v1/admin/catalog/item-requests", admin);
        assertThat(queue.data().path("total").asLong()).isEqualTo(1);

        // 通过时由运营给出**最终**区间（不是照抄建议值），编码自动排下一个序号
        ApiClient.ApiCall approved = api.post("/api/v1/admin/catalog/item-requests/" + proposalId + "/approve",
                new CatalogItemProposalApproveRequest("180.00", "360.00", "次", "宠物针灸（含疗程）",
                        null, "线上已核价"), admin);
        assertCodeOk(approved, "通过提案");
        assertThat(approved.data().path("status").asInt()).isEqualTo(1);
        assertThat(approved.data().path("item_code").asText()).startsWith("HE-");
        assertThat(approved.data().path("review_logs")).hasSize(2);
        String itemCode = approved.data().path("item_code").asText();

        // 目录里有了它，区间是运营给的那一组
        ApiClient.ApiCall items = api.get("/api/v1/provider/catalog/items?keyword=宠物针灸",
                provider.token());
        assertThat(items.data().path("total").asLong()).isEqualTo(1);
        assertThat(items.data().path("list").get(0).path("code").asText()).isEqualTo(itemCode);
        assertThat(items.data().path("list").get(0).path("name").asText()).isEqualTo("宠物针灸（含疗程）");
        assertThat(items.data().path("list").get(0).path("price_min").asText()).isEqualTo("180.00");
        assertThat(items.data().path("list").get(0).path("price_max").asText()).isEqualTo("360.00");

        // 服务者用新项目定价（区间校验同样生效）
        assertCodeOk(createListing(provider.token(), itemCode, "200.00"), "按新目录项定价");
        assertThat(createListing(provider.token(), itemCode, "100.00").code()).isEqualTo(90001);

        // 通过后不能再审一次
        assertThat(api.post("/api/v1/admin/catalog/item-requests/" + proposalId + "/approve",
                new CatalogItemProposalApproveRequest("180.00", "360.00", null, null, null, null), admin)
                .code()).isEqualTo(40900);
    }

    @Test
    @DisplayName("驳回要写原因；驳回后提案状态与原因都能被服务者看到")
    void rejectedProposalShowsReason() {
        String admin = adminToken();
        ApprovedProvider provider = createApprovedProvider("LIC-PROPOSAL-002");
        long proposalId = submitProposal(provider.token(), "GROOMING", "宠物染色", "300.00", "600.00")
                .data().path("id").asLong();

        // 原因必填：空原因的驳回等于让服务者去猜
        assertThat(api.post("/api/v1/admin/catalog/item-requests/" + proposalId + "/reject",
                Map.of("reason", "   "), admin).code()).isEqualTo(40001);

        ApiClient.ApiCall rejected = api.post("/api/v1/admin/catalog/item-requests/" + proposalId + "/reject",
                Map.of("reason", "涉及动物福利风险，暂不开放"), admin);
        assertCodeOk(rejected, "驳回");
        assertThat(rejected.data().path("status").asInt()).isEqualTo(2);
        assertThat(rejected.data().path("reject_reason").asText()).isEqualTo("涉及动物福利风险，暂不开放");

        ApiClient.ApiCall mine = api.get("/api/v1/provider/catalog/item-requests/" + proposalId,
                provider.token());
        assertThat(mine.data().path("status").asInt()).isEqualTo(2);
        assertThat(mine.data().path("review_logs").get(1).path("actor_domain").asText()).isEqualTo("admin");

        // 被驳回的提案不会变成目录项
        assertThat(api.get("/api/v1/provider/catalog/items?keyword=宠物染色", provider.token())
                .data().path("total").asLong()).isZero();
    }

    @Test
    @DisplayName("未通过入驻审核不能提案；分类不存在也要在入口拦住")
    void proposalRequiresApprovedProviderAndKnownCategory() {
        // 还没审核通过的服务者
        ProviderActor pending = providerActor();
        ApiClient.ApiCall submitted = submitApplication(pending.token(), "待审医院", "13800001111",
                "LIC-PROPOSAL-003", null);
        assertCodeOk(submitted, "提交申请");
        ApiClient.ApiCall blocked = submitProposal(pending.token(), "HOSPITAL", "术前咨询", "50.00", "100.00");
        assertThat(blocked.code()).isEqualTo(40300);
        assertThat(blocked.message()).contains("审核");

        ApprovedProvider provider = createApprovedProvider("LIC-PROPOSAL-004");
        ApiClient.ApiCall unknownCategory = submitProposal(provider.token(), "NOT_EXIST", "奇怪的项目",
                "50.00", "100.00");
        assertThat(unknownCategory.code()).isEqualTo(40001);

        // 建议区间自身要合法
        assertThat(submitProposal(provider.token(), "HOSPITAL", "区间写反", "300.00", "100.00").code())
                .isEqualTo(40001);
        assertThat(submitProposal(provider.token(), "HOSPITAL", "小数太多", "100.005", "200.00").code())
                .isEqualTo(40001);
    }

    @Test
    @DisplayName("运营指定编码时就按它建项；编码已被占用则 40900（不可复用）")
    void explicitCodeMustBeFreeAndMatchPrefix() {
        String admin = adminToken();
        ApprovedProvider provider = createApprovedProvider("LIC-PROPOSAL-005");
        long proposalId = submitProposal(provider.token(), "HOSPITAL", "远程问诊", "30.00", "80.00")
                .data().path("id").asLong();

        // 前缀不符（HE-xxx 才能进医院）
        assertThat(api.post("/api/v1/admin/catalog/item-requests/" + proposalId + "/approve",
                new CatalogItemProposalApproveRequest("30.00", "80.00", null, null, "GR-099", null), admin)
                .code()).isEqualTo(40001);

        // 已被占用的编码：只能报错，不能自动改号
        assertThat(api.post("/api/v1/admin/catalog/item-requests/" + proposalId + "/approve",
                new CatalogItemProposalApproveRequest("30.00", "80.00", null, null, "HE-001", null), admin)
                .code()).isEqualTo(40900);

        // 换成没人用的编码可以
        ApiClient.ApiCall approved = api.post("/api/v1/admin/catalog/item-requests/" + proposalId + "/approve",
                new CatalogItemProposalApproveRequest("30.00", "80.00", null, null, "HE-900", null), admin);
        assertCodeOk(approved, "指定编码通过");
        assertThat(approved.data().path("item_code").asText()).isEqualTo("HE-900");
    }

    @Test
    @DisplayName("越权：别人的提案按不存在处理（40400）")
    void otherProvidersProposalIsNotFound() {
        ApprovedProvider first = createApprovedProvider("LIC-PROPOSAL-006");
        long proposalId = submitProposal(first.token(), "HOSPITAL", "术后护理", "100.00", "200.00")
                .data().path("id").asLong();

        ApprovedProvider second = createApprovedProvider("LIC-PROPOSAL-007");
        assertThat(api.get("/api/v1/provider/catalog/item-requests/" + proposalId, second.token()).code())
                .isEqualTo(40400);
        // 自己的列表里也没有别人的提案
        assertThat(api.get("/api/v1/provider/catalog/item-requests", second.token())
                .data().path("total").asLong()).isZero();
    }
}
