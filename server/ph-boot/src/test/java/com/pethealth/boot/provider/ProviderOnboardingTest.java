package com.pethealth.boot.provider;

import com.pethealth.boot.support.ApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 服务者入驻与资质审核（切片 #104，决策见 ADR-0035）：
 * 申请 → 单人审核 → 驳回可重提 → 通过后成为正式服务者。
 *
 * <p>覆盖的规则：状态流转（驳回后重提**不新建服务者**）、审核流水、审核通过前不能维护门店、
 * 越权（别人的申请 40400）、营业执照唯一性（被驳回的不占名额）、冻结 / 解冻。
 */
class ProviderOnboardingTest extends ProviderApiTestSupport {

    @Test
    @DisplayName("提交申请：建一条待审核的服务者记录 + 一条「提交」审核流水")
    void submitCreatesPendingProviderAndReviewLog() {
        ProviderActor actor = providerActor();
        ApiClient.ApiCall submitted = submitApplication(actor.token(), "康宠动物医院", "13800001111",
                "LIC-SUBMIT-001", null);

        assertCodeOk(submitted, "提交入驻申请");
        long applicationId = submitted.data().path("id").asLong();
        assertThat(submitted.data().path("status").asInt()).isZero();
        assertThat(submitted.data().path("submit_count").asInt()).isEqualTo(1);
        assertThat(submitted.data().path("provider").path("status").asInt()).isZero();
        assertThat(submitted.data().path("provider").path("name").asText()).isEqualTo("康宠动物医院");
        // 材料：证件号脱敏（保留前 4 后 4，中间打码）、明文不出现在响应里
        assertThat(submitted.data().path("qualifications")).hasSize(1);
        String maskedCertNo = submitted.data().path("qualifications").get(0).path("cert_no").asText();
        assertThat(maskedCertNo).startsWith("LIC-").endsWith("001").contains("***");
        assertThat(maskedCertNo).isNotEqualTo("LIC-SUBMIT-001");
        assertThat(submitted.body().toString()).doesNotContain("LIC-SUBMIT-001");
        assertThat(submitted.data().path("review_logs")).hasSize(1);
        assertThat(submitted.data().path("review_logs").get(0).path("action").asInt()).isEqualTo(1);
        assertThat(submitted.data().path("review_logs").get(0).path("actor_domain").asText())
                .isEqualTo("provider");

        // 联系电话脱敏（库里的电话是密文，接口只给 138****1111）
        assertThat(submitted.data().path("contact_phone").asText()).isEqualTo("138****1111");

        ApiClient.ApiCall mine = api.get("/api/v1/provider/onboarding/applications", actor.token());
        assertCodeOk(mine, "我的申请列表");
        assertThat(mine.data().path("list")).hasSize(1);
        assertThat(mine.data().path("list").get(0).path("id").asLong()).isEqualTo(applicationId);
        assertThat(mine.data().path("list").get(0).path("provider_name").asText())
                .isEqualTo("康宠动物医院");
    }

    @Test
    @DisplayName("审核驳回 → 修改重提：仍是同一份申请，提交次数加一，不新建服务者")
    void rejectThenResubmitKeepsSameApplication() {
        String admin = adminToken();
        ProviderActor actor = providerActor();
        ApiClient.ApiCall submitted = submitApplication(actor.token(), "康宠动物医院", "13800001111",
                "LIC-RESUBMIT-001", null);
        assertCodeOk(submitted, "提交入驻申请");
        long applicationId = submitted.data().path("id").asLong();
        long providerId = submitted.data().path("provider").path("id").asLong();

        // 运营队列默认只给待审核
        ApiClient.ApiCall queue = api.get("/api/v1/admin/provider-applications", admin);
        assertCodeOk(queue, "审核队列");
        assertThat(queue.data().path("total").asLong()).isEqualTo(1);

        ApiClient.ApiCall rejected = api.post("/api/v1/admin/provider-applications/" + applicationId + "/reject",
                Map.of("reason", "营业执照照片不清晰"), admin);
        assertCodeOk(rejected, "驳回");
        assertThat(rejected.data().path("status").asInt()).isEqualTo(2);
        assertThat(rejected.data().path("reject_reason").asText()).isEqualTo("营业执照照片不清晰");

        // 服务者侧看得见驳回原因
        ApiClient.ApiCall mine = api.get("/api/v1/provider/onboarding/applications/" + applicationId,
                actor.token());
        assertThat(mine.data().path("status").asInt()).isEqualTo(2);
        assertThat(mine.data().path("reject_reason").asText()).isEqualTo("营业执照照片不清晰");
        assertThat(mine.data().path("provider").path("status").asInt()).isEqualTo(2);

        // 还没通过审核：门店读不到（40400，前端要走申请单看进度），写被拒且说清是「已被驳回」
        assertThat(api.get("/api/v1/provider/profile", actor.token()).code()).isEqualTo(40400);
        ApiClient.ApiCall updateBefore = api.put("/api/v1/provider/profile",
                profilePayload("康宠动物医院（改）"), actor.token());
        assertThat(updateBefore.code()).isEqualTo(40300);
        assertThat(updateBefore.message()).contains("驳回");

        // 修改重提：**同一份申请单**（POST 新提一份会被拒，见下）
        ApiClient.ApiCall resubmit = api.put("/api/v1/provider/onboarding/applications/" + applicationId,
                application("康宠动物医院（重提）", "13800001111", "LIC-RESUBMIT-001", null), actor.token());
        assertThat(resubmit.code()).isZero();
        assertThat(resubmit.data().path("id").asLong()).isEqualTo(applicationId);
        assertThat(resubmit.data().path("provider").path("id").asLong()).isEqualTo(providerId);
        assertThat(resubmit.data().path("submit_count").asInt()).isEqualTo(2);
        assertThat(resubmit.data().path("reject_reason").isNull()).isTrue();
        assertThat(resubmit.data().path("provider").path("name").asText()).isEqualTo("康宠动物医院（重提）");

        // 流水：提交 → 驳回 → 重提（append-only，历史都在）
        assertThat(resubmit.data().path("review_logs")).hasSize(3);
        assertThat(resubmit.data().path("review_logs").get(1).path("action").asInt()).isEqualTo(4);
        assertThat(resubmit.data().path("review_logs").get(1).path("remark").asText())
                .isEqualTo("营业执照照片不清晰");
        assertThat(resubmit.data().path("review_logs").get(2).path("action").asInt()).isEqualTo(2);

        // 已有一份申请单的账号不能再用 POST 新建（否则一次驳回会留下一个空壳服务者）
        ApiClient.ApiCall duplicated = submitApplication(actor.token(), "康宠动物医院（另一份）",
                "13800001111", "LIC-RESUBMIT-002", null);
        assertThat(duplicated.code()).isEqualTo(40900);
        assertThat(duplicated.message()).contains("入驻申请");
    }

    @Test
    @DisplayName("审核通过：申请人被绑定为管理员，之后可维护门店信息与营业时间")
    void approveBindsAdminAndAllowsProfileMaintenance() {
        String admin = adminToken();
        ProviderActor actor = providerActor();
        ApiClient.ApiCall submitted = submitApplication(actor.token(), "康宠动物医院", "13800001111",
                "LIC-APPROVE-001", null);
        assertCodeOk(submitted, "提交");
        long applicationId = submitted.data().path("id").asLong();
        long providerId = submitted.data().path("provider").path("id").asLong();

        // 通过之前：门店这个资源还不存在（40400），写操作被拒且说清是「审核中」
        assertThat(api.get("/api/v1/provider/profile", actor.token()).code()).isEqualTo(40400);
        ApiClient.ApiCall before = api.put("/api/v1/provider/profile",
                profilePayload("康宠动物医院（偷改）"), actor.token());
        assertThat(before.code()).isEqualTo(40300);
        assertThat(before.message()).contains("审核中");

        ApiClient.ApiCall approved = api.post(
                "/api/v1/admin/provider-applications/" + applicationId + "/approve",
                Map.of("remark", "材料齐全"), admin);
        assertCodeOk(approved, "审核通过");
        assertThat(approved.data().path("status").asInt()).isEqualTo(1);
        assertThat(approved.data().path("provider").path("status").asInt()).isEqualTo(1);
        assertThat(approved.data().path("provider").path("approved_at").isNull()).isFalse();
        assertThat(approved.data().path("qualifications").get(0).path("status").asInt()).isEqualTo(1);

        // 通过之后：维护门店信息（电话在响应里仍是脱敏值）
        ApiClient.ApiCall updated = api.put("/api/v1/provider/profile",
                profilePayload("康宠动物医院（总院）"), actor.token());
        assertCodeOk(updated, "维护门店");
        assertThat(updated.data().path("name").asText()).isEqualTo("康宠动物医院（总院）");
        assertThat(updated.data().path("phone").asText()).isEqualTo("138****1111");

        // 营业时间：整体替换，按星期升序返回
        ApiClient.ApiCall hours = api.put("/api/v1/provider/profile/business-hours",
                Map.of("hours", java.util.List.of(
                        Map.of("day_of_week", 7, "open_time", "10:00", "close_time", "18:00"),
                        Map.of("day_of_week", 1, "open_time", "09:00", "close_time", "20:30"))),
                actor.token());
        assertCodeOk(hours, "维护营业时间");
        assertThat(hours.data().path("business_hours")).hasSize(2);
        assertThat(hours.data().path("business_hours").get(0).path("day_of_week").asInt()).isEqualTo(1);
        assertThat(hours.data().path("business_hours").get(0).path("close_time").asText()).isEqualTo("20:30");

        // 同一天重复 / 开始时间不早于结束时间都要被拦（40001）
        assertThat(api.put("/api/v1/provider/profile/business-hours",
                Map.of("hours", java.util.List.of(
                        Map.of("day_of_week", 1, "open_time", "09:00", "close_time", "10:00"),
                        Map.of("day_of_week", 1, "open_time", "11:00", "close_time", "12:00"))),
                actor.token()).code()).isEqualTo(40001);
        assertThat(api.put("/api/v1/provider/profile/business-hours",
                Map.of("hours", java.util.List.of(
                        Map.of("day_of_week", 2, "open_time", "18:00", "close_time", "09:00"))),
                actor.token()).code()).isEqualTo(40001);

        // 通过之后不能重复审核
        assertThat(api.post("/api/v1/admin/provider-applications/" + applicationId + "/approve",
                Map.of(), admin).code()).isEqualTo(40900);
        assertThat(providerId).isPositive();
    }

    @Test
    @DisplayName("越权：别人的申请按不存在处理（40400）")
    void otherApplicantsApplicationIsNotFound() {
        ProviderActor first = providerActor();
        ApiClient.ApiCall submitted = submitApplication(first.token(), "康宠动物医院", "13800001111",
                "LIC-OTHER-001", null);
        long applicationId = submitted.data().path("id").asLong();

        ProviderActor second = providerActor();
        assertThat(api.get("/api/v1/provider/onboarding/applications/" + applicationId, second.token()).code())
                .isEqualTo(40400);
        assertThat(api.put("/api/v1/provider/onboarding/applications/" + applicationId,
                application("别人改的", "13800001111", "LIC-OTHER-002", null), second.token()).code())
                .isEqualTo(40400);
    }

    @Test
    @DisplayName("同一营业执照只能挂一个有效服务者；被驳回的不占名额")
    void licenseUniquenessIgnoresRejectedProviders() {
        String admin = adminToken();
        ProviderActor first = providerActor();
        ApiClient.ApiCall firstSubmit = submitApplication(first.token(), "康宠动物医院", "13800001111",
                "LIC-UNIQUE-001", null);
        assertCodeOk(firstSubmit, "第一家提交");

        // 第二家用同一张执照：被拒（那正是唯一性校验存在的意义）
        ProviderActor second = providerActor();
        ApiClient.ApiCall conflict = submitApplication(second.token(), "另一家动物医院", "13800002222",
                "LIC-UNIQUE-001", null);
        assertThat(conflict.code()).isEqualTo(40900);

        // 第一家被驳回后，执照不再被占用——否则一次误拒就再也开不了店
        assertCodeOk(api.post("/api/v1/admin/provider-applications/"
                + firstSubmit.data().path("id").asLong() + "/reject", Map.of("reason", "材料不全"), admin),
                "驳回第一家");
        ApiClient.ApiCall retry = submitApplication(second.token(), "另一家动物医院", "13800002222",
                "LIC-UNIQUE-001", null);
        assertCodeOk(retry, "驳回后第二家可提交");

        // 同一账号不能同时有两份待审核申请
        assertThat(submitApplication(second.token(), "另一家动物医院", "13800002222", "LIC-UNIQUE-002", null)
                .code()).isEqualTo(40900);
    }

    @Test
    @DisplayName("冻结：禁止维护门店；解冻后恢复；被驳回的服务者不能直接解冻")
    void freezeBlocksOperationsAndOnlyFrozenCanBeUnfrozen() {
        String admin = adminToken();
        ApprovedProvider provider = createApprovedProvider("LIC-FREEZE-001");

        ApiClient.ApiCall frozen = api.put("/api/v1/admin/providers/" + provider.providerId() + "/status",
                Map.of("status", 3, "reason", "多次违规"), admin);
        assertCodeOk(frozen, "冻结");
        assertThat(frozen.data().path("status").asInt()).isEqualTo(3);

        // 冻结 = 禁止新上架与新订单（这里落成「不能新增选品、不能维护门店」）
        assertThat(createListing(provider.token(), "HE-004", "150.00").code()).isEqualTo(40300);
        assertThat(api.put("/api/v1/provider/profile", profilePayload("冻结期间改名"), provider.token()).code())
                .isEqualTo(40300);

        ApiClient.ApiCall unfrozen = api.put("/api/v1/admin/providers/" + provider.providerId() + "/status",
                Map.of("status", 1), admin);
        assertCodeOk(unfrozen, "解冻");
        assertThat(unfrozen.data().path("status").asInt()).isEqualTo(1);
        assertCodeOk(createListing(provider.token(), "HE-004", "150.00"), "解冻后选品");

        // 被驳回的服务者不能从这里恢复：必须重新走申请审核
        ProviderActor rejectedActor = providerActor();
        ApiClient.ApiCall submitted = submitApplication(rejectedActor.token(), "待驳回医院", "13800003333",
                "LIC-FREEZE-002", null);
        assertCodeOk(api.post("/api/v1/admin/provider-applications/"
                + submitted.data().path("id").asLong() + "/reject", Map.of("reason", "资质不符"), admin),
                "驳回");
        long rejectedProviderId = submitted.data().path("provider").path("id").asLong();
        assertThat(api.put("/api/v1/admin/providers/" + rejectedProviderId + "/status",
                Map.of("status", 1), admin).code()).isEqualTo(40900);

        // 冻结与解冻都留了流水（谁、何时、结论）
        Long logs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM provider_review_log WHERE target_type = 4 AND provider_id = ?",
                Long.class, provider.providerId());
        assertThat(logs).isEqualTo(2L);
    }

    @Test
    @DisplayName("分页边界：page=0 与 page_size=101 都是参数错误，page_size=1 时 has_more 正确")
    void pagingBoundaries() {
        String admin = adminToken();
        createApprovedProvider("LIC-PAGE-001");
        createApprovedProvider("LIC-PAGE-002");

        assertThat(api.get("/api/v1/admin/provider-applications?page=0", admin).code()).isEqualTo(40001);
        assertThat(api.get("/api/v1/admin/provider-applications?page_size=101", admin).code()).isEqualTo(40001);
        assertThat(api.get("/api/v1/admin/providers?page=0", admin).code()).isEqualTo(40001);
        assertThat(api.get("/api/v1/admin/providers?status=9", admin).code()).isEqualTo(40001);

        ApiClient.ApiCall firstPage = api.get("/api/v1/admin/providers?page=1&page_size=1", admin);
        assertCodeOk(firstPage, "第一页");
        assertThat(firstPage.data().path("list")).hasSize(1);
        assertThat(firstPage.data().path("total").asLong()).isEqualTo(2);
        assertThat(firstPage.data().path("has_more").asBoolean()).isTrue();
        assertThat(firstPage.data().path("page_size").asLong()).isEqualTo(1);

        // 越界页返回空列表，不回到第一页（回到第一页会让前端以为请求到了别的内容）
        ApiClient.ApiCall overflow = api.get("/api/v1/admin/providers?page=99&page_size=1", admin);
        assertThat(overflow.data().path("list")).isEmpty();
    }

    private java.util.Map<String, Object> profilePayload(String name) {
        return Map.of("name", name, "address", "上海市徐汇区测试路 1 号", "phone", "13800001111",
                "intro", "测试门店");
    }
}
