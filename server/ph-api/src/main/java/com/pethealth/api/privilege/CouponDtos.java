package com.pethealth.api.privilege;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 券池的接口 DTO，与 {@code contract/provider.yaml} 与 {@code contract/admin.yaml} 的
 * {@code Coupon*} 组件对齐（两块契约共用同一份后端 DTO——契约各写一遍是生成物的固有重复）。
 *
 * <p>三条贯穿这里的口径：
 *
 * <ul>
 *   <li><b>金额一律是字符串</b>（{@code "30.00"}），最多两位小数（ADR-0011：JS 里丢精度）。
 *       解析与校验在 {@code com.pethealth.privilege.service.CouponMoney} 一处做，不在注解里抄规则；
 *   <li><b>券不产生资金</b>（ADR-0036）：`faceValue` 是抵扣额、`costBearer` 是「算谁头上」，
 *       这里没有任何余额、结算、打款字段——本项目钱在门店付；
 *   <li><b>额度是算出来的</b>：贡献视图里同时给承诺 / 已发放 / 已核销 / 占用中 / 已过期 / 可发放，
 *       让「还能发多少」这个问题在前端有一个明确答案，而不是让前端自己减。
 * </ul>
 */
public final class CouponDtos {

    private CouponDtos() {
    }

    /** 券模板视图（服务者侧与运营侧共用）。 */
    public record CouponTemplateView(
            Long id,
            String code,
            String name,
            String faceValue,
            String minAmount,
            Integer validDays,
            Integer costBearer,
            Integer scopeType,
            List<String> scopeCodes,
            String scopeDesc,
            Integer issueLimit,
            Integer issuedCount,
            Integer status,
            String description,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    /**
     * 新建 / 修改券模板。
     *
     * <p>{@code code} 与 {@code costBearer} **创建后不可改**：编码挂在券实例与适用范围上，
     * 成本归属一旦有人按它承诺过额度，改了就改了考核的归属。改这两项一律 40001，不静默忽略。
     */
    public record CouponTemplateRequest(
            @NotBlank(message = "模板编码不能为空")
            @Pattern(regexp = "^[A-Z]{2}-\\d{3}$", message = "模板编码形如 CP-001：两位大写字母 + 三位数字")
            String code,

            @NotBlank(message = "券名不能为空")
            @Size(max = 128, message = "券名最长 128 个字符")
            String name,

            @NotBlank(message = "面额不能为空")
            String faceValue,

            String minAmount,

            @NotNull(message = "有效期天数必填")
            @Min(value = 1, message = "有效期至少 1 天")
            @Max(value = 3650, message = "有效期最长 3650 天")
            Integer validDays,

            @NotNull(message = "成本归属必填")
            @Min(value = 1, message = "成本归属只能是 1 服务者成本 / 2 平台补贴")
            @Max(value = 2, message = "成本归属只能是 1 服务者成本 / 2 平台补贴")
            Integer costBearer,

            @Min(value = 0, message = "适用范围只能是 0 不限 / 1 限分类 / 2 限目录项")
            @Max(value = 2, message = "适用范围只能是 0 不限 / 1 限分类 / 2 限目录项")
            Integer scopeType,

            List<String> scopeCodes,

            @Min(value = 1, message = "发放上限至少 1")
            Integer issueLimit,

            @Size(max = 255, message = "说明最长 255 个字符")
            String description) {
    }

    /** 券模板上下架。 */
    public record CouponTemplateStatusRequest(
            @NotNull(message = "状态必填")
            @Min(value = 0, message = "状态只能是 1 启用 / 0 停用")
            @Max(value = 1, message = "状态只能是 1 启用 / 0 停用")
            Integer status) {
    }

    /** 券池总览（含对账口径）。 */
    public record CouponPoolOverviewView(
            Integer templateCount,
            Integer templateActiveCount,
            Integer providerCostTemplateCount,
            Integer platformSubsidyTemplateCount,
            Integer contributionCount,
            Integer committedTotal,
            Integer availableTotal,
            Integer issuedTotal,
            Integer redeemedTotal,
            Integer reservedTotal,
            Integer expiredTotal,
            List<CouponSourceStatView> bySource,
            CouponReconciliationView reconciliation) {
    }

    /** 按来源拆开的一笔账。 */
    public record CouponSourceStatView(
            Integer source,
            Integer issued,
            Integer redeemed,
            Integer reserved,
            Integer expired) {
    }

    /** 对账：实例数 = 已发放 = 已核销 + 未过期未核销 + 已过期未核销。 */
    public record CouponReconciliationView(
            Integer issued,
            Integer redeemed,
            Integer reserved,
            Integer expired,
            Boolean balanced,
            String note) {
    }

    /**
     * 券实例视图。
     *
     * <p>{@code faceValue} / {@code minAmount} 是**发放时的快照**：模板后来改了面额，
     * 已经发到用户手里的券不受影响——券是平台对用户的承诺（与目录项「现取不存快照」相反）。
     *
     * <p><b>{@code applies} / {@code recommended} 只在查询带了门店 / 金额时才有值</b>
     * （契约 {@code GET /coupons} 的 {@code provider_id} / {@code amount} / {@code service_code}）：
     * 前者是「这张券能不能用在这一单」的**完整判定**（状态、有效期、门槛、门店、服务项范围，
     * 与下单时那条 80001 复核**同一份实现**），后者标记「服务端挑出的最优可用券」——每个列表最多一张。
     * 不带那几个参数时为 {@code null}；`null` 与 `false` 是两件事（没判 vs 判过不能用）。
     */
    public record CouponView(
            Long id,
            String code,
            Long userId,
            Long providerId,
            String providerName,
            Long templateId,
            String templateCode,
            String templateName,
            String faceValue,
            String minAmount,
            Integer source,
            Long contributionId,
            Integer status,
            LocalDateTime validFrom,
            LocalDateTime validUntil,
            LocalDateTime issuedAt,
            LocalDateTime redeemedAt,
            LocalDateTime createdAt,
            Boolean applies,
            Boolean recommended) {
    }

    /**
     * 服务者的券贡献（列表与详情共用）。
     *
     * <p>{@code logs} 只在详情里有值（列表要点开才看流水），其余字段两处一致——
     * 列表最关心的就是额度账，把额度账藏进详情等于让服务者多点一次才能看到「还能发多少」。
     */
    public record CouponContributionView(
            Long id,
            Long providerId,
            String providerName,
            Long templateId,
            String templateCode,
            String templateName,
            String faceValue,
            String minAmount,
            String scopeDesc,
            Integer totalCount,
            Integer issuedCount,
            Integer redeemedCount,
            Integer reservedCount,
            Integer expiredCount,
            Integer availableCount,
            String completionRate,
            Integer status,
            String remark,
            List<CouponContributionLogView> logs,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    /**
     * 承诺或调整额度。
     *
     * <p>{@code status} 不传表示「不改状态」；传 1 让已停止的那条重新生效（配合新额度），
     * 传 2 等价于撤回。`POST` 时不传按 1 处理。
     */
    public record CouponContributionRequest(
            @NotNull(message = "券模板必填")
            Long templateId,

            @NotNull(message = "承诺额度必填")
            @Min(value = 1, message = "承诺额度必须是正整数")
            Integer totalCount,

            @Min(value = 1, message = "状态只能是 1 生效中 / 2 已停止发放")
            @Max(value = 2, message = "状态只能是 1 生效中 / 2 已停止发放")
            Integer status,

            @Size(max = 255, message = "说明最长 255 个字符")
            String remark) {
    }

    /** 额度流水（append-only）。 */
    public record CouponContributionLogView(
            Long id,
            Integer action,
            Integer totalCount,
            String remark,
            Long operatorId,
            LocalDateTime createdAt) {
    }

    /** 运营定向发放平台补贴券。 */
    public record IssueCouponRequest(
            @NotNull(message = "发给谁必填")
            Long userId,

            @NotNull(message = "券模板必填")
            Long templateId,

            @Size(max = 255, message = "说明最长 255 个字符")
            String remark) {
    }
}
